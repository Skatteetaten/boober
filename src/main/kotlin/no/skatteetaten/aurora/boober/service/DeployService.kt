package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.openshift.Application
import no.skatteetaten.aurora.boober.model.openshift.ApplicationSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ExternalResourceProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.deploymentConfigFromJson
import no.skatteetaten.aurora.boober.utils.filterNullValues
import no.skatteetaten.aurora.boober.utils.imageStreamFromJson
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.whenFalse
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
// TODO:Split up. Service is to large
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
    @Value("\${boober.docker.registry}") val dockerRegistry: String
) {

    val logger: Logger = LoggerFactory.getLogger(DeployService::class.java)

    @JvmOverloads
    fun executeDeploy(
        ref: AuroraConfigRef,
        applicationIds: List<ApplicationId>,
        overrides: List<AuroraConfigFile> = listOf(),
        deploy: Boolean = true
    ): List<AuroraDeployResult> {

        if (applicationIds.isEmpty()) {
            throw IllegalArgumentException("Specify applicationId")
        }

        val deploymentSpecs = auroraConfigService.createValidatedAuroraDeploymentSpecs(ref, applicationIds, overrides)
        val environments = prepareDeployEnvironments(deploymentSpecs)
        val deployResults: List<AuroraDeployResult> = deployFromSpecs(deploymentSpecs, environments, deploy)

        deployLogService.markRelease(ref, deployResults)

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
                    Pair(
                        environment,
                        AuroraDeployResult(
                            success = false,
                            reason = "User=${authenticatedUser.fullName} does not have access to admin this environment from the groups=${environment.permissions.admin.groups}"
                        )
                    )
                }

                val projectExist = openShiftClient.projectExists(environment.namespace)
                val environmentResponses = prepareDeployEnvironment(environment, projectExist)

                val success = environmentResponses.all { it.success }

                val message = if (!success) {
                    "One or more http calls to OpenShift failed"
                } else "Namespace created successfully."

                logger.info("Environment done. user='${authenticatedUser.fullName}' namespace=${environment.namespace} success=$success reason=$message admins=${environment.permissions.admin.groups} viewers=${environment.permissions.view?.groups}")
                Pair(
                    environment, AuroraDeployResult(
                        openShiftResponses = environmentResponses,
                        success = success,
                        reason = message,
                        projectExist = projectExist
                    )
                )
            }.toMap()
    }

    private fun prepareDeployEnvironment(
        environment: AuroraDeployEnvironment,
        projectExist: Boolean
    ): List<OpenShiftResponse> {
        val namespaceName = environment.namespace

        val projectResponse = projectExist.whenFalse {
            openShiftCommandBuilder.generateProjectRequest(environment).let {
                openShiftClient.performOpenShiftCommand(namespaceName, it)
                    .also { Thread.sleep(2000) }
            }
        }

        val namespace = openShiftCommandBuilder.generateNamespace(environment)
        val roleBindings = openShiftCommandBuilder.generateRolebindings(environment)

        val resourceResponse = roleBindings.addIfNotNull(namespace)
            .map { openShiftClient.performOpenShiftCommand(namespaceName, it) }
        return listOfNotNull(projectResponse).addIfNotNull(resourceResponse)
    }

    private fun deployFromSpecs(
        deploymentSpecs: List<AuroraDeploymentSpec>,
        environments: Map<AuroraDeployEnvironment, AuroraDeployResult>,
        deploy: Boolean
    ): List<AuroraDeployResult> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        return deploymentSpecs.map {
            val env = environments[it.environment]
            when {
                env == null -> {
                    if (it.cluster != cluster) {
                        AuroraDeployResult(
                            auroraDeploymentSpec = it,
                            ignored = true,
                            reason = "Not valid in this cluster."
                        )
                    } else {
                        AuroraDeployResult(
                            auroraDeploymentSpec = it,
                            success = false,
                            reason = "Environment was not created."
                        )
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

    fun createCommonLabels(
        auroraDeploymentSpec: AuroraDeploymentSpec,
        deployId: String,
        additionalLabels: Map<String, String> = mapOf(),
        name: String = auroraDeploymentSpec.name
    ): Map<String, String> {
        val labels = mapOf(
            "app" to name,
            "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
            "affiliation" to auroraDeploymentSpec.environment.affiliation,
            "updateInBoober" to "true",
            "booberDeployId" to deployId
        )

        val deploy = auroraDeploymentSpec.deploy ?: return OpenShiftObjectLabelService.toOpenShiftLabelNameSafeMap(
            labels + additionalLabels
        )
        return OpenShiftObjectLabelService.toOpenShiftLabelNameSafeMap(
            mapOf("appId" to DigestUtils.sha1Hex("${deploy.groupId}/${deploy.artifactId}")) + labels + additionalLabels
        )
    }

    fun createAnnotations(spec: AuroraDeploymentSpec): Map<String, String> {

        val deploy = spec.deploy!!
        fun escapeOverrides(): String? {
            val files =
                deploy.overrideFiles.mapValues { jacksonObjectMapper().readValue(it.value, JsonNode::class.java) }
            val content = jacksonObjectMapper().writeValueAsString(files)
            return content.takeIf { it != "{}" }
        }

        return mapOf(
            "boober.skatteetaten.no/applicationFile" to spec.applicationFile.name,
            "console.skatteetaten.no/alarm" to deploy.flags.alarm.toString(),
            "boober.skatteetaten.no/overrides" to escapeOverrides(),
            "console.skatteetaten.no/management-path" to deploy.managementPath,
            "boober.skatteetaten.no/releaseTo" to deploy.releaseTo,
            "sprocket.sits.no/deployment-config.certificate" to spec.integration?.certificateCn
        ).filterNullValues().filterValues { !it.isBlank() }
    }

    fun deployFromSpec(
        deploymentSpec: AuroraDeploymentSpec,
        shouldDeploy: Boolean,
        namespaceCreated: Boolean
    ): AuroraDeployResult {

        val deployId = UUID.randomUUID().toString().substring(0, 7)

        if (deploymentSpec.cluster != cluster) {
            return AuroraDeployResult(
                auroraDeploymentSpec = deploymentSpec,
                ignored = true,
                reason = "Not valid in this cluster."
            )
        }
        //Here we need to create application object

        //TODO need ref for what is deployed
        val application = Application(
            spec = ApplicationSpec(deploymentSpec.fields),
            metadata = ObjectMetaBuilder()
                .withName(deploymentSpec.name)
                .withLabels(createCommonLabels(deploymentSpec, deployId))
                .withAnnotations(createAnnotations(deploymentSpec))
                .build()
        )

        val applicationCommnd = openShiftCommandBuilder.createOpenShiftCommand(
            deploymentSpec.environment.namespace,
            jacksonObjectMapper().convertValue(application)
        )
        val applicationResult =
            openShiftClient.performOpenShiftCommand(deploymentSpec.environment.namespace, applicationCommnd)
        val appResponse: Application = applicationResult.responseBody?.let {
            jacksonObjectMapper().convertValue<Application>(it)
        } ?: throw RuntimeException("Could not write application")

        //  logger.info("App is={}", appResponse)
        val ownerReference = OwnerReferenceBuilder()
            .withApiVersion(appResponse.apiVersion)
            .withKind(appResponse.kind)
            .withName(appResponse.metadata.name)
            .withUid(appResponse.metadata.uid)
            .build()
        //This should be set in each generated resource

        logger.debug("Resource provisioning")
        val provisioningResult = resourceProvisioner.provisionResources(deploymentSpec)

        logger.debug("Apply objects")
        val openShiftResponses: List<OpenShiftResponse> = applyOpenShiftApplicationObjects(
            deployId, deploymentSpec, provisioningResult, namespaceCreated, ownerReference
        )

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
            return result.copy(
                openShiftResponses = openShiftResponses.addIfNotNull(redeployResult.openShiftResponses),
                tagResponse = tagResult, success = false, reason = redeployResult.message
            )
        }

        return result.copy(
            openShiftResponses = openShiftResponses.addIfNotNull(redeployResult.openShiftResponses),
            tagResponse = tagResult,
            reason = "Deployment success."
        )
    }

    private fun applyOpenShiftApplicationObjects(
        deployId: String,
        deploymentSpec: AuroraDeploymentSpec,
        provisioningResult: ProvisioningResult? = null,
        mergeWithExistingResource: Boolean,
        ownerReference: OwnerReference
    ): List<OpenShiftResponse> {

        val namespace = deploymentSpec.environment.namespace
        val name = deploymentSpec.name

        val openShiftApplicationResponses: List<OpenShiftResponse> = openShiftCommandBuilder
            .generateApplicationObjects(
                deployId,
                deploymentSpec,
                provisioningResult,
                mergeWithExistingResource,
                ownerReference
            )
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
