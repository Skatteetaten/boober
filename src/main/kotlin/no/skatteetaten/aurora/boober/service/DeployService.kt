package no.skatteetaten.aurora.boober.service

import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ExternalResourceProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.deploymentConfigFromJson
import no.skatteetaten.aurora.boober.utils.imageStreamFromJson
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
//TODO:Split up. Service is to large
class DeployService(
        val auroraConfigService: AuroraConfigService,
        val openShiftCommandBuilder: OpenShiftCommandBuilder,
        val openShiftClient: OpenShiftClient,
        val dockerService: DockerService,
        val resourceProvisioner: ExternalResourceProvisioner,
        val redeployService: RedeployService,
        val userDetailsProvider: UserDetailsProvider,
        val deployLogService: DeployLogService,
        @Value("\${openshift.cluster}") val cluster: String,
        @Value("\${boober.docker.registry}") val dockerRegistry: String) {

    val logger: Logger = LoggerFactory.getLogger(DeployService::class.java)

    @JvmOverloads
    fun executeDeploy(auroraConfigName: String, applicationIds: List<ApplicationId>, overrides: List<AuroraConfigFile> = listOf(), deploy: Boolean = true): List<AuroraDeployResult> {

        if (applicationIds.isEmpty()) {
            throw IllegalArgumentException("Specify applicationId")
        }

        val deploymentSpecs = auroraConfigService.createValidatedAuroraDeploymentSpecs(auroraConfigName, applicationIds, overrides)
        val environments = prepareDeployEnvironments(deploymentSpecs)
        val deployResults: List<AuroraDeployResult> = deployFromSpecs(deploymentSpecs, environments, deploy)

        deployLogService.markRelease(auroraConfigName, deployResults)

        return deployResults
    }


    fun prepareDeployEnvironments(deploymentSpecs: List<AuroraDeploymentSpec>): Map<AuroraDeployEnvironment, AuroraDeployResult> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        return deploymentSpecs
                .filter { it.cluster == cluster }
                .map { it.environment }
                .distinct()
                .map { environment: AuroraDeployEnvironment ->

                    if (!authenticatedUser.hasAnyRole(environment.permissions.admin.groups)) {
                        Pair(environment, AuroraDeployResult(success = false, reason = "User=${authenticatedUser.fullName} does not have access to admin this environment from the groups=${environment.permissions.admin.groups}"))
                    }

                    val projectExist = openShiftClient.projectExists(environment.namespace)
                    val environmentResponses = prepareDeployEnvironment(environment, projectExist)

                    val success = environmentResponses.all { it.success }

                    val message = if (!success) {
                        "One or more http calls to OpenShift failed"
                    } else "Namespace created successfully."

                    logger.info("Environment done. user='${authenticatedUser.fullName}' namespace=${environment.namespace} success=${success} reason=${message} admins=${environment.permissions.admin.groups} viewers=${environment.permissions.view?.groups}")
                    Pair(environment, AuroraDeployResult(
                            openShiftResponses = environmentResponses,
                            success = success,
                            reason = message,
                            projectExist = projectExist))
                }.toMap()
    }

    private fun prepareDeployEnvironment(environment: AuroraDeployEnvironment, projectExist: Boolean): List<OpenShiftResponse> {
        val namespaceName = environment.namespace
        val namespace = openShiftCommandBuilder.generateNamespace(environment)
        val roleBindings = openShiftCommandBuilder.generateRolebindings(environment)
        val projectCmd = if (!projectExist) openShiftCommandBuilder.generateProjectRequest(environment) else null
        return listOfNotNull(projectCmd).addIfNotNull(roleBindings).addIfNotNull(namespace)
                .map { openShiftClient.performOpenShiftCommand(namespaceName, it) }
    }

    private fun deployFromSpecs(deploymentSpecs: List<AuroraDeploymentSpec>, environments: Map<AuroraDeployEnvironment, AuroraDeployResult>, deploy: Boolean): List<AuroraDeployResult> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        return deploymentSpecs.map {
            val env = environments[it.environment]
            when {
                env == null -> {
                    if (it.cluster != cluster) {
                        AuroraDeployResult(auroraDeploymentSpec = it, ignored = true, reason = "Not valid in this cluster.")
                    } else {
                        AuroraDeployResult(auroraDeploymentSpec = it, success = false, reason = "Environment was not created.")
                    }
                }
                !env.success -> env.copy(auroraDeploymentSpec = it)
                else -> {
                    try {
                        val result = deployFromSpec(it, deploy, env.projectExist)
                        result.copy(openShiftResponses = env.openShiftResponses.addIfNotNull(result.openShiftResponses))
                    } catch (e: Exception) {
                        AuroraDeployResult(auroraDeploymentSpec = it, success = false, reason = e.message)
                    }
                }
            }.also {
                logger.info("Deploy done username=${authenticatedUser.username} fullName='${authenticatedUser.fullName}' deployId=${it.deployId} app=${it.auroraDeploymentSpec?.name} namespace=${it.auroraDeploymentSpec?.environment?.namespace} success=${it.success} ignored=${it.ignored} reason=${it.reason}")
            }
        }
    }

    fun deployFromSpec(deploymentSpec: AuroraDeploymentSpec, shouldDeploy: Boolean, namespaceCreated: Boolean): AuroraDeployResult {

        val deployId = UUID.randomUUID().toString().substring(0, 7)
        if (deploymentSpec.cluster != cluster) {
            return AuroraDeployResult(auroraDeploymentSpec = deploymentSpec, ignored = true, reason = "Not valid in this cluster.")
        }

        logger.debug("Resource provisioning")
        val provisioningResult = resourceProvisioner.provisionResources(deploymentSpec)

        logger.debug("Apply objects")
        val openShiftResponses: List<OpenShiftResponse> = applyOpenShiftApplicationObjects(
                deployId, deploymentSpec, provisioningResult, namespaceCreated)

        logger.debug("done applying objects")
        val success = openShiftResponses.all { it.success }
        val result = AuroraDeployResult(deploymentSpec, deployId, openShiftResponses, success)
        if (!shouldDeploy) {
            return result.copy(reason = "Deploy explicitly turned of.")
        }

        if (!success) {
            return result.copy(reason = "One or more resources did not complete correctly.")
        }

        if (deploymentSpec.deploy?.flags?.pause == true) {
            return result.copy(reason = "Deployment is paused.")
        }

        val tagResult = deploymentSpec.deploy?.takeIf { it.releaseTo != null }?.let {
            val dockerGroup = it.groupId.dockerGroupSafeName()
            val cmd = TagCommand("$dockerGroup/${it.artifactId}", it.version, it.releaseTo!!, dockerRegistry)
            dockerService.tag(cmd)
        }

        tagResult?.takeIf { !it.success }
                ?.let { return result.copy(tagResponse = it, reason = "Tag command failed") }

        val imageStream = findImageStreamResponse(openShiftResponses)
        val deploymentConfig = findDeploymentConfigResponse(openShiftResponses)
                ?: throw IllegalArgumentException("Missing DeploymentConfig")
        val redeployResult = if (deploymentSpec.type == TemplateType.development) {
            RedeployService.RedeployResult(message = "No deploy was made with ${deploymentSpec.type} type")
        } else {
            redeployService.triggerRedeploy(deploymentConfig, imageStream)
        }

        if (!redeployResult.success) {
            return result.copy(openShiftResponses = openShiftResponses.addIfNotNull(redeployResult.openShiftResponses),
                    tagResponse = tagResult, success = false, reason = redeployResult.message)
        }

        return result.copy(openShiftResponses = openShiftResponses.addIfNotNull(redeployResult.openShiftResponses), tagResponse = tagResult,
                reason = "Deployment success.")
    }

    private fun applyOpenShiftApplicationObjects(deployId: String, deploymentSpec: AuroraDeploymentSpec,
                                                 provisioningResult: ProvisioningResult? = null,
                                                 mergeWithExistingResource: Boolean): List<OpenShiftResponse> {

        val namespace = deploymentSpec.environment.namespace
        val name = deploymentSpec.name

        val openShiftApplicationResponses: List<OpenShiftResponse> = openShiftCommandBuilder
                .generateApplicationObjects(deployId, deploymentSpec, provisioningResult, mergeWithExistingResource)
                .map { openShiftClient.performOpenShiftCommand(namespace, it) }

        if (openShiftApplicationResponses.any { !it.success }) {
            logger.warn("One or more commands failed for $namespace/$name. Will not delete objects from previous deploys.")
            return openShiftApplicationResponses
        }

        val deleteOldObjectResponses = openShiftCommandBuilder
                .createOpenShiftDeleteCommands(name, namespace, deployId)
                .map { openShiftClient.performOpenShiftCommand(namespace, it) }

        return openShiftApplicationResponses.addIfNotNull(deleteOldObjectResponses)
    }

    private fun findImageStreamResponse(openShiftResponses: List<OpenShiftResponse>): ImageStream? {
        return openShiftResponses.find { it.responseBody?.openshiftKind == "imagestream" }
                ?.let { imageStreamFromJson(it.responseBody) }
    }

    private fun findDeploymentConfigResponse(openShiftResponses: List<OpenShiftResponse>): DeploymentConfig? {
        return openShiftResponses.find { it.responseBody?.openshiftKind == "deploymentconfig" }
                ?.let { deploymentConfigFromJson(it.responseBody) }
    }
}

