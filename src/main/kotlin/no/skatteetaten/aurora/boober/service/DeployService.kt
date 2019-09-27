package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.openshift.api.model.OpenshiftRoleBinding
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.feature.dockerImagePath
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.feature.pause
import no.skatteetaten.aurora.boober.feature.releaseTo
import no.skatteetaten.aurora.boober.feature.type
import no.skatteetaten.aurora.boober.feature.version
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.createAuroraDeploymentCommand
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

data class AuroraDeployEnvironment(val namespace: String, val resources: List<AuroraResource>) {

    val adminUsers: List<String>
        get() {
            return resources.find { it.name == "RoleBinding-admin" }?.let {
                val rb: OpenshiftRoleBinding = jacksonObjectMapper().convertValue(it.resource)
                rb.userNames
            } ?: emptyList()
        }

    val viewGroups: List<String>
        get() {
            return resources.find { it.name == "RoleBinding-view" }?.let {
                val rb: OpenshiftRoleBinding = jacksonObjectMapper().convertValue(it.resource)
                rb.groupNames
            } ?: emptyList()
        }

    val adminGroups: List<String>
        get() {
            return resources.find { it.name == "RoleBinding-admin" }?.let {
                val rb: OpenshiftRoleBinding = jacksonObjectMapper().convertValue(it.resource)
                rb.groupNames
            } ?: emptyList()
        }
}

@Service
// TODO:Split up. Service is to large
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

        val auroraConfigRefExact = auroraConfigService.findExactRef(ref)?.let { ref.copy(resolvedRef = it) } ?: ref
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigRefExact)

        val commands = applicationDeploymentRefs.map {
            val deployId = UUID.randomUUID().toString().substring(0, 7)
            createAuroraDeploymentCommand(auroraConfig, it, overrides, deployId, auroraConfigRefExact)
        }

        val deploymentCtx = auroraDeploymentSpecService.createValidatedAuroraDeploymentContexts(commands)

        val usedOverrideNames: List<String> = deploymentCtx.flatMap { ctx -> ctx.cmd.applicationFiles.filter { it.override } }.map { it.configName }

        val unusedOverrides = overrides.filter { !usedOverrideNames.contains(it.configName) }
        if (unusedOverrides.isNotEmpty()) {
            val overrideString = unusedOverrides.joinToString(",") { it.name }
            val refString = applicationDeploymentRefs.joinToString(",")
            throw IllegalArgumentException(
                    "Overrides files '$overrideString' does not apply to any deploymentReference ($refString)"
            )
        }

        val ctxResources: Map<AuroraDeploymentContext, Set<AuroraResource>> = deploymentCtx.associate {
            it to it.createResources()
        }

        val environments = prepareDeployEnvironments(ctxResources)
        val deployResults: List<AuroraDeployResult> = deployFromSpecs(ctxResources, environments, deploy)

        val deployer = userDetailsProvider.getAuthenticatedUser().let {
            Deployer(it.fullName ?: it.username, "${it.username}@skatteetaten.no")
        }
        return deployLogService.markRelease(deployResults, deployer)
    }


    fun prepareDeployEnvironments(ctxResources: Map<AuroraDeploymentContext, Set<AuroraResource>>): Map<String, AuroraDeployResult> {
        return ctxResources
                .filter { it.key.spec.cluster == cluster }
                .map { (ctx, resources) -> AuroraDeployEnvironment(ctx.spec.namespace, resources.filter { it.header }) }
                .distinct()
                .associate {
                    prepareDeployEnvironment(it)
                }

    }

    fun prepareDeployEnvironment(environment: AuroraDeployEnvironment): Pair<String, AuroraDeployResult> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        val userNotInAdminUsers = !environment.adminUsers.contains(authenticatedUser.username)
        val adminGroups = environment.adminGroups
        val viewGroups = environment.viewGroups
        val userNotInAnyAdminGroups = !authenticatedUser.hasAnyRole(adminGroups)

        if (userNotInAdminUsers && userNotInAnyAdminGroups) {
            return Pair(
                    environment.namespace,
                    AuroraDeployResult(
                            success = false,
                            reason = "User=${authenticatedUser.fullName} does not have access to admin this environment from the groups=${adminGroups}"
                    )
            )
        }

        val projectExist = openShiftClient.projectExists(environment.namespace)
        val environmentResponses = prepareDeployEnvironment2(environment, projectExist)

        val success = environmentResponses.all { it.success }

        val message = if (!success) {
            "One or more http calls to OpenShift failed"
        } else "Namespace created successfully."

        logger.info("Environment done. user='${authenticatedUser.fullName}' namespace=${environment.namespace} success=$success reason=$message admins=${adminGroups} viewers=${viewGroups}")

        return Pair(
                environment.namespace, AuroraDeployResult(
                openShiftResponses = environmentResponses,
                success = success,
                reason = message,
                projectExist = projectExist))
    }

    fun prepareDeployEnvironment2(
            environment: AuroraDeployEnvironment,
            projectExist: Boolean
    ): List<OpenShiftResponse> {
        val namespaceName = environment.namespace

        val projectResponse = projectExist.whenFalse {
            openShiftCommandBuilder.createOpenShiftCommand(
                    newResource = environment.resources.find { it.resource.kind == "ProjectRequest" }?.resource
                            ?: throw Exception("Could not find project request"),
                    mergeWithExistingResource = false,
                    retryGetResourceOnFailure = false
            ).let {
                openShiftClient.performOpenShiftCommand(namespaceName, it)
                        .also { Thread.sleep(2000) }
            }
        }
        val resources = environment.resources.filter { it.resource.kind != "ProjectRequest" }.map {
            openShiftCommandBuilder.createOpenShiftCommand(
                namespace = it.resource.metadata.namespace,
                newResource = it.resource,
                retryGetResourceOnFailure = true
            )
        }

        val resourceResponse = resources.map { openShiftClient.performOpenShiftCommand(namespaceName, it) }
        return listOfNotNull(projectResponse).addIfNotNull(resourceResponse)
    }

    private fun deployFromSpecs(
            deploymentContexts: Map<AuroraDeploymentContext, Set<AuroraResource>>,
            environments: Map<String, AuroraDeployResult>,
            deploy: Boolean
    ): List<AuroraDeployResult> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        return deploymentContexts.map {

            val cmd = it.key.cmd
            val spec = it.key.spec
            val adc = no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand(
                    cmd.overrideFiles,
                    cmd.adr,
                    cmd.auroraConfigRef)

            val env = environments[spec.namespace]
            when {
                env == null -> {
                    if (spec.cluster != cluster) {
                        AuroraDeployResult(
                                auroraDeploymentSpecInternal = spec,
                                ignored = true,
                                reason = "Not valid in this cluster.",
                                command = adc)
                    } else {
                        AuroraDeployResult(
                                auroraDeploymentSpecInternal = spec,
                                success = false,
                                reason = "Environment was not created.",
                                command = adc
                        )
                    }
                }
                !env.success -> env.copy(auroraDeploymentSpecInternal = spec)
                else -> {
                    try {
                        val result = deployFromSpec(it.key, it.value, deploy, env.projectExist)
                        result.copy(openShiftResponses = env.openShiftResponses.addIfNotNull(result.openShiftResponses))
                    } catch (e: Exception) {
                        AuroraDeployResult(
                                auroraDeploymentSpecInternal = spec,
                                success = false,
                                reason = e.message,
                                command = no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand(
                                        cmd.overrideFiles,
                                        cmd.adr,
                                        cmd.auroraConfigRef)
                        )
                    }
                }
            }.also {
                logger.info("Deploy done username=${authenticatedUser.username} fullName='${authenticatedUser.fullName}' deployId=${it.deployId} app=${it.auroraDeploymentSpecInternal?.name} namespace=${it.auroraDeploymentSpecInternal?.namespace} success=${it.success} ignored=${it.ignored} reason=${it.reason}")
            }
        }
    }

    fun deployFromSpec(
            context: AuroraDeploymentContext,
            resources: Set<AuroraResource>,
            shouldDeploy: Boolean,
            namespaceCreated: Boolean
    ): AuroraDeployResult {


        if (context.spec.cluster != cluster) {
            return AuroraDeployResult(
                    auroraDeploymentSpecInternal = context.spec,
                    ignored = true,
                    reason = "Not valid in this cluster.",
                    command = no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand(
                            context.cmd.overrideFiles,
                            context.cmd.adr,
                            context.cmd.auroraConfigRef)
            )
        }

        val application = resources.first {
            it.resource.kind == "ApplicationDeployment"
        }.resource

        val applicationCommand = openShiftCommandBuilder.createOpenShiftCommand(
                context.spec.namespace, application
        )

        val applicationResult = openShiftClient.performOpenShiftCommand(context.spec.namespace, applicationCommand)

        //TODO: Problem with deserializing ApplicationDeployment
        val appResponse = applicationResult.responseBody

        if (appResponse == null) {
            return AuroraDeployResult(
                    auroraDeploymentSpecInternal = context.spec,
                    deployId = context.cmd.deployId,
                    openShiftResponses = listOf(applicationResult),
                    success = false,
                    reason = "Creating application object failed",
                    command = no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand(
                            context.cmd.overrideFiles,
                            context.cmd.adr,
                            context.cmd.auroraConfigRef)
            )
        }

        val ownerReferenceUid = appResponse.at("/metadata/uid").textValue()

        val tagResult = context.spec.takeIf { it.releaseTo != null }?.let {
            dockerService.tag(TagCommand(it.dockerImagePath, it.version, it.releaseTo!!, dockerRegistry))
        }
        val rawResult = AuroraDeployResult(
                command = no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand(
                        context.cmd.overrideFiles,
                        context.cmd.adr,
                        context.cmd.auroraConfigRef),
                deployId = context.cmd.deployId,
                auroraDeploymentSpecInternal = context.spec,
                tagResponse = tagResult
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

        if (!shouldDeploy) {
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
