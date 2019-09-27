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
        val auroraDeploymentSpecService: AuroraDeploymentSpecService,
        val openShiftCommandBuilder: OpenShiftCommandService,
        val openShiftClient: OpenShiftClient,
        val dockerService: DockerService,
        val redeployService: RedeployService,
        val userDetailsProvider: UserDetailsProvider,
        val deployLogService: DeployLogService,
        @Value("\${openshift.cluster}") val cluster: String,
        @Value("\${integrations.docker.registry}") val dockerRegistry: String
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

        val auroraConfigRefExact = auroraConfigService.resolveToExactRef(ref)
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigRefExact)

        val commands = applicationDeploymentRefs.map {
            val deployId = UUID.randomUUID().toString().substring(0, 7)
            AuroraContextCommand(auroraConfig, it, auroraConfigRefExact, overrides, deployId, deploy)
        }

        val deploymentCtx = auroraDeploymentSpecService.createValidatedAuroraDeploymentContexts(commands)
        validateUnusedOverrideFiles(deploymentCtx, overrides, applicationDeploymentRefs)

        val (validContexts, invalidContexts) = deploymentCtx.partition { it.spec.cluster == cluster }
        //TODO handle error here

        val envDeploys: Map<String, List<AuroraDeployCommand>> = validContexts.groupBy({ it.spec.namespace }) { context ->
            val (header, normal) = context.createResources().partition { it.header }
            AuroraDeployCommand(
                    headerResources = header.toSet(),
                    resources = normal.toSet(),
                    context = context,
                    deployId = UUID.randomUUID().toString().substring(0, 7),
                    shouldDeploy = deploy,
                    user = userDetailsProvider.getAuthenticatedUser()
            )
        }


        val deployResults: Map<String, List<AuroraDeployResult>> = envDeploys.toMap().mapValues { (ns, commands) ->
            val env = prepareDeployEnvironment(ns, commands.first().headerResources)

            if (!env.success) {
                throw Exception("handle this error")
            } else {
                commands.map {
                    val result = deployFromSpec(it, env)
                    result.copy(openShiftResponses = env.openShiftResponses.addIfNotNull(result.openShiftResponses))
                }
            }
        }
        //TODO: remove
        val deployer = userDetailsProvider.getAuthenticatedUser().let {
            Deployer(it.fullName ?: it.username, "${it.username}@skatteetaten.no")
        }
        return deployLogService.markRelease(deployResults.flatMap { it.value }, deployer)
    }

    private fun validateUnusedOverrideFiles(deploymentCtx: List<AuroraDeploymentContext>, overrides: List<AuroraConfigFile>, applicationDeploymentRefs: List<ApplicationDeploymentRef>) {
        val usedOverrideNames: List<String> = deploymentCtx.flatMap { ctx -> ctx.cmd.applicationFiles.filter { it.override } }.map { it.configName }

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

        logger.info("Environment done. user='${authenticatedUser.fullName}' namespace=${namespace} success=$success reason=$message")

        return AuroraEnvironmentResult(
                openShiftResponses = environmentResponses,
                success = success,
                reason = message,
                projectExist = projectExist)
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

        //TODO: Problem with deserializing ApplicationDeployment
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
            dockerService.tag(TagCommand(it.dockerImagePath, it.version, it.releaseTo!!, dockerRegistry))
        }
        val rawResult = AuroraDeployResult(
                tagResponse = tagResult,
                deployCommand = cmd,
                projectExist = env.projectExist
        )
        tagResult?.takeIf { !it.success }?.let {
            return rawResult.copy(
                    success = false,
                    reason = "Tag command failed."
            )
        }

        logger.debug("Apply objects")
        val openShiftResponses: List<OpenShiftResponse> = listOf(applicationResult) +
                applyOpenShiftApplicationObjects(
                        resources, context, namespaceCreated, ownerReferenceUid
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
            resources: Set<AuroraResource>,
            context: AuroraDeploymentContext,
            mergeWithExistingResource: Boolean,
            ownerReferenceUid: String
    ): List<OpenShiftResponse> {

        val namespace = context.spec.namespace
        val name = context.spec.name

        //TODO: Should we fix deployId here aswell?
        val jsonResources = resources.filter { !it.header }.map {
            it.resource.metadata.ownerReferences.find {
                it.kind == "ApplicationDeployment"
            }?.let {
                it.uid = ownerReferenceUid
            }
            jacksonObjectMapper().convertValue<JsonNode>(it.resource)
        }

        val objects = openShiftCommandBuilder.orderObjects(jsonResources, context.spec.type, context.spec.namespace, mergeWithExistingResource)

        val openShiftApplicationResponses: List<OpenShiftResponse> = objects.flatMap {
            openShiftCommandBuilder.createAndApplyObjects(namespace, it, mergeWithExistingResource)
        }

        if (openShiftApplicationResponses.any { !it.success }) {
            logger.warn("One or more commands failed for $namespace/$name. Will not delete objects from previous deploys.")
            return openShiftApplicationResponses
        }

        val deleteOldObjectResponses = openShiftCommandBuilder
                .createOpenShiftDeleteCommands(name, namespace, context.cmd.deployId)
                .map { openShiftClient.performOpenShiftCommand(namespace, it) }

        return openShiftApplicationResponses.addIfNotNull(deleteOldObjectResponses)
    }
}
