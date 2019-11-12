package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.ApplicationDeploymentFeature
import no.skatteetaten.aurora.boober.feature.dockerImagePath
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.feature.namespace
import no.skatteetaten.aurora.boober.feature.pause
import no.skatteetaten.aurora.boober.feature.releaseTo
import no.skatteetaten.aurora.boober.feature.type
import no.skatteetaten.aurora.boober.feature.version
import no.skatteetaten.aurora.boober.model.AuroraDeployCommand
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.AuroraEnvironmentResult
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.OpenShiftCommandService
import no.skatteetaten.aurora.boober.service.RedeployService
import no.skatteetaten.aurora.boober.service.TagCommand
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.whenFalse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.util.StopWatch

private val logger = KotlinLogging.logger { }

@Service
class OpenShiftDeployer(
    val openShiftCommandBuilder: OpenShiftCommandService,
    val openShiftClient: OpenShiftClient,
    val cantusService: CantusService,
    val redeployService: RedeployService,
    val userDetailsProvider: UserDetailsProvider,
    @Value("\${integrations.docker.registry}") val dockerRegistry: String,
    @Value("\${integrations.docker.releases}") val releaseDockerRegistry: String
) {
    fun performDeployCommands(deployCommands: List<AuroraDeployCommand>): Map<String, List<AuroraDeployResult>> {

        val watch = StopWatch()
        val envDeploys: Map<String, List<AuroraDeployCommand>> = deployCommands.groupBy { it.context.spec.namespace }

        return envDeploys.mapValues { (ns, commands) ->
            watch.start("env-$ns")
            val env = prepareDeployEnvironment(ns, commands.first().headerResources)
            watch.stop()

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
                    watch.start("apply-${it.context.cmd.applicationDeploymentRef}")
                    val result = deployFromSpec(it, env)
                    watch.stop()
                    result.copy(openShiftResponses = env.openShiftResponses.addIfNotNull(result.openShiftResponses))
                }
            }
        }.also {
            logger.info("Perform deploy ${watch.prettyPrint()}")
        }
    }

    private fun prepareDeployEnvironment(namespace: String, resources: Set<AuroraResource>): AuroraEnvironmentResult {

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

    private fun deployFromSpec(
        cmd: AuroraDeployCommand,
        env: AuroraEnvironmentResult
    ): AuroraDeployResult {
        val projectExist = env.projectExist
        val context = cmd.context
        val resources = cmd.resources
        val application = resources.first {
            it.resource.kind == "ApplicationDeployment"
        }.resource
        application.metadata.labels = application.metadata.labels.addIfNotNull("booberDeployId" to cmd.deployId)
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
                TagCommand(
                    name = it.dockerImagePath,
                    from = it.version,
                    to = it.releaseTo!!,
                    fromRegistry = dockerRegistry,
                    toRegistry = releaseDockerRegistry
                )
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
                cmd, projectExist, ownerReferenceUid
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

        val jsonResources = deployCommand.resources.filter {
            it.createdSource.feature != ApplicationDeploymentFeature::class.java
        }.map { resource ->
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
