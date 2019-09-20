package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.feature.dockerImagePath
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.feature.pause
import no.skatteetaten.aurora.boober.feature.releaseTo
import no.skatteetaten.aurora.boober.feature.type
import no.skatteetaten.aurora.boober.feature.version
import no.skatteetaten.aurora.boober.mapper.AuroraContextCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeployCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.createResources
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.describeString
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.whenFalse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class DeployService(
    val auroraConfigService: AuroraConfigService,
    val auroraDeploymentContextService: AuroraDeploymentContextService,
    val openShiftCommandBuilder: OpenShiftCommandService,
    val openShiftClient: OpenShiftClient,
    val cantusService: CantusService,
    val redeployService: RedeployService,
    val userDetailsProvider: UserDetailsProvider,
    val deployLogService: DeployLogService,
    @Value("\${openshift.cluster}") val cluster: String,
    @Value("\${integrations.docker.registry}") val dockerRegistry: String,
    @Value("\${integrations.docker.releases}") val releaseDockerRegistry: String
) {

    val logger: Logger = LoggerFactory.getLogger(DeployService::class.java)

    @JvmOverloads
    fun executeDeploy(
        ref: AuroraConfigRef,
        applicationDeploymentRefs: List<ApplicationDeploymentRef>,
        overrides: List<AuroraConfigFile> = listOf(),
        deploy: Boolean = true
    ): List<AuroraDeployResult> {

        if (applicationDeploymentRefs.isEmpty()) {
            throw IllegalArgumentException("Specify applicationDeploymentRef")
        }

        val commands = createContextCommands(ref, applicationDeploymentRefs, overrides)

        val validContexts = createAuroraDeploymentContexts(commands)

        val deployCommands = createDeployCommands(validContexts, deploy)

        val envDeploys: Map<String, List<AuroraDeployCommand>> = deployCommands.groupBy { it.context.spec.namespace }

        val deployResults: Map<String, List<AuroraDeployResult>> = envDeploys.mapValues { (ns, commands) ->
            val env = prepareDeployEnvironment(ns, commands.first().headerResources)

            if (!env.success) {
                commands.map {
                    AuroraDeployResult(
                        projectExist = env.projectExist,
                        deployCommand = it,
                        success = env.success,
                        reason = env.reason,
                        openShiftResponses = env.openShiftResponses
                    )
                }
            } else {
                commands.map {
                    val result = deployFromSpec(it, env)
                    result.copy(openShiftResponses = env.openShiftResponses.addIfNotNull(result.openShiftResponses))
                }
            }
        }
        val deployer = userDetailsProvider.getAuthenticatedUser().let {
            Deployer(it.fullName ?: it.username, "${it.username}@skatteetaten.no")
        }
        return deployLogService.markRelease(deployResults.flatMap { it.value }, deployer)
    }

    private fun createDeployCommands(
        validContexts: List<AuroraDeploymentContext>,
        deploy: Boolean
    ): List<AuroraDeployCommand> {
        val result: List<Pair<List<ContextErrors>, AuroraDeployCommand?>> = validContexts.map { context ->
            val (errors, resourceResults) = context.createResources()
            when {
                errors.isNotEmpty() -> errors to null
                resourceResults == null -> listOf(
                    ContextErrors(
                        context.cmd,
                        listOf(RuntimeException("No resources generated"))
                    )
                ) to null
                else -> {

                    val (header, normal) = resourceResults.partition { it.header }
                    emptyList<ContextErrors>() to AuroraDeployCommand(
                        headerResources = header.toSet(),
                        resources = normal.toSet(),
                        context = context,
                        deployId = UUID.randomUUID().toString().substring(0, 7),
                        shouldDeploy = deploy,
                        user = userDetailsProvider.getAuthenticatedUser()
                    )
                }
            }
        }

        val resourceErrors = result.flatMap { it.first }
        if (resourceErrors.isNotEmpty()) {
            throw MultiApplicationValidationException(resourceErrors)
        }

        return result.mapNotNull { it.second }
    }

    private fun createAuroraDeploymentContexts(commands: List<AuroraContextCommand>): List<AuroraDeploymentContext> {
        val deploymentCtx = auroraDeploymentContextService.createValidatedAuroraDeploymentContexts(commands)
        validateUnusedOverrideFiles(deploymentCtx)

        val (validContexts, invalidContexts) = deploymentCtx.partition { it.spec.cluster == cluster }

        if (invalidContexts.isNotEmpty()) {
            val errors = invalidContexts.map {
                ContextErrors(it.cmd, listOf(java.lang.IllegalArgumentException("Not valid in this cluster")))
            }
            throw MultiApplicationValidationException(errors)
        }
        return validContexts
    }

    private fun createContextCommands(
        ref: AuroraConfigRef,
        applicationDeploymentRefs: List<ApplicationDeploymentRef>,
        overrides: List<AuroraConfigFile>
    ): List<AuroraContextCommand> {
        val auroraConfigRefExact = auroraConfigService.resolveToExactRef(ref)
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigRefExact)

        return applicationDeploymentRefs.map {
            AuroraContextCommand(auroraConfig, it, auroraConfigRefExact, overrides)
        }
    }

    private fun validateUnusedOverrideFiles(deploymentCtx: List<AuroraDeploymentContext>) {
        val overrides = deploymentCtx.first().cmd.overrides
        val usedOverrideNames: List<String> =
            deploymentCtx.flatMap { ctx -> ctx.cmd.applicationFiles.filter { it.override } }.map { it.configName }

        val applicationDeploymentRefs = deploymentCtx.map { it.cmd.applicationDeploymentRef }
        val unusedOverrides = overrides.filter { !usedOverrideNames.contains(it.configName) }
        if (unusedOverrides.isNotEmpty()) {
            val overrideString = unusedOverrides.joinToString(",") { it.name }
            val refString = applicationDeploymentRefs.joinToString(",")
            throw IllegalArgumentException(
                "Overrides files '$overrideString' does not apply to any deploymentReference ($refString)"
            )
        }
    }

    fun prepareDeployEnvironment(namespace: String, resources: Set<AuroraResource>): AuroraEnvironmentResult {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        val projectExist = openShiftClient.projectExists(namespace)
        val projectResponse = projectExist.whenFalse {
            openShiftCommandBuilder.createOpenShiftCommand(
                newResource = resources.find { it.resource.kind == "ProjectRequest" }?.resource
                    ?: throw Exception("Could not find project request"),
                mergeWithExistingResource = false,
                retryGetResourceOnFailure = false
            ).let {
                openShiftClient.performOpenShiftCommand(namespace, it)
                    .also { Thread.sleep(2000) }
            }
        }
        val otherEnvResources = resources.filter { it.resource.kind != "ProjectRequest" }.map {
            openShiftCommandBuilder.createOpenShiftCommand(
                namespace = it.resource.metadata.namespace,
                newResource = it.resource,
                retryGetResourceOnFailure = true
            )
        }
        val resourceResponse = otherEnvResources.map { openShiftClient.performOpenShiftCommand(namespace, it) }
        val environmentResponses = listOfNotNull(projectResponse).addIfNotNull(resourceResponse)

        val success = environmentResponses.all { it.success }

        val message = if (!success) {
            "One or more http calls to OpenShift failed"
        } else "Namespace created successfully."

        logger.info("Environment done. user='${authenticatedUser.fullName}' namespace=$namespace success=$success reason=$message")

        return AuroraEnvironmentResult(
            openShiftResponses = environmentResponses,
            success = success,
            reason = message,
            projectExist = projectExist
        )
    }

    fun deployFromSpec(
        cmd: AuroraDeployCommand,
        env: AuroraEnvironmentResult
    ): AuroraDeployResult {
        val namespaceCreated = !env.projectExist
        val context = cmd.context
        val resources = cmd.resources
        val application = resources.first {
            it.resource.kind == "ApplicationDeployment"
        }.resource

        val applicationCommand = openShiftCommandBuilder.createOpenShiftCommand(context.spec.namespace, application)
        val applicationResult = openShiftClient.performOpenShiftCommand(context.spec.namespace, applicationCommand)
        val appResponse = applicationResult.responseBody

        if (appResponse == null) {
            return AuroraDeployResult(
                deployCommand = cmd,
                openShiftResponses = listOf(applicationResult),
                success = false,
                reason = "Creating application object failed"
            )
        }

        val ownerReferenceUid = appResponse.at("/metadata/uid").textValue()

        val tagResult = context.spec.takeIf { it.releaseTo != null }?.let {
            cantusService.tag(
                TagCommand(it.dockerImagePath, it.version, it.releaseTo!!, dockerRegistry)
            )
        }

        val rawResult = AuroraDeployResult(
            tagResponse = tagResult,
            deployCommand = cmd,
            projectExist = env.projectExist
        )

        logger.info("TagResult=${tagResult?.success}")
        if (tagResult != null && !tagResult.success) {
            logger.info("tag result not successfull")
            return rawResult.copy(
                success = false,
                reason = "Tag command failed."
            )
        }

        logger.debug("Apply objects")
        val openShiftResponses: List<OpenShiftResponse> = listOf(applicationResult) +
            applyOpenShiftApplicationObjects(
                cmd, namespaceCreated, ownerReferenceUid
            )

        logger.debug("done applying objects")
        val success = openShiftResponses.all { it.success }
        val result = rawResult.copy(
            openShiftResponses = openShiftResponses,
            success = success
        )

        if (!success) {
            val failedCommands = openShiftResponses.filter { !it.success }.describeString()
            return result.copy(reason = "Errors $failedCommands")
        }

        if (!cmd.shouldDeploy) {
            return result.copy(reason = "No deploy made, turned off in payload.")
        }

        if (context.spec.pause) {
            return result.copy(reason = "Deployment is paused and will be/remain scaled down.")
        }

        val redeployResult = redeployService.triggerRedeploy(openShiftResponses, context.spec.type)

        if (!redeployResult.success) {
            return result.copy(
                openShiftResponses = openShiftResponses.addIfNotNull(redeployResult.openShiftResponses),
                tagResponse = tagResult, success = false, reason = redeployResult.message
            )
        }

        return result.copy(
            openShiftResponses = openShiftResponses.addIfNotNull(redeployResult.openShiftResponses),
            tagResponse = tagResult,
            reason = redeployResult.message
        )
    }

    private fun applyOpenShiftApplicationObjects(
        deployCommand: AuroraDeployCommand,
        mergeWithExistingResource: Boolean,
        ownerReferenceUid: String
    ): List<OpenShiftResponse> {

        val context = deployCommand.context
        val namespace = context.spec.namespace
        val name = context.spec.name

        val jsonResources = deployCommand.resources.map { resource ->
            resource.resource.metadata.labels =
                resource.resource.metadata.labels.addIfNotNull("booberDeployId" to deployCommand.deployId)
            resource.resource.metadata.ownerReferences.find {
                it.kind == "ApplicationDeployment"
            }?.let {
                it.uid = ownerReferenceUid
            }
            jacksonObjectMapper().convertValue<JsonNode>(resource.resource)
        }

        val objects = openShiftCommandBuilder.orderObjects(
            jsonResources,
            context.spec.type,
            context.spec.namespace,
            mergeWithExistingResource
        )

        val openShiftApplicationResponses: List<OpenShiftResponse> = objects.flatMap {
            openShiftCommandBuilder.createAndApplyObjects(namespace, it, mergeWithExistingResource)
        }

        if (openShiftApplicationResponses.any { !it.success }) {
            logger.warn("One or more commands failed for $namespace/$name. Will not delete objects from previous deploys.")
            return openShiftApplicationResponses
        }

        val deleteOldObjectResponses = openShiftCommandBuilder
            .createOpenShiftDeleteCommands(name, namespace, deployCommand.deployId)
            .map { openShiftClient.performOpenShiftCommand(namespace, it) }

        return openShiftApplicationResponses.addIfNotNull(deleteOldObjectResponses)
    }
}
