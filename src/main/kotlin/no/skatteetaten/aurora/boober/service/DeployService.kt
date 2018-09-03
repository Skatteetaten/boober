package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ExternalResourceProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.*
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*

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
            applicationDeploymentRefs: List<ApplicationDeploymentRef>,
            overrides: List<AuroraConfigFile> = listOf(),
            deploy: Boolean = true
    ): List<AuroraDeployResult> {

        if (applicationDeploymentRefs.isEmpty()) {
            throw IllegalArgumentException("Specify applicationDeploymentRef")
        }

        val auroraConfigRefExact = auroraConfigService.findExactRef(ref)?.let { ref.copy(resolvedRef = it) } ?: ref

        val deploymentSpecs =
                auroraConfigService.createValidatedAuroraDeploymentSpecs(auroraConfigRefExact, applicationDeploymentRefs, overrides)
        val environments = prepareDeployEnvironments(deploymentSpecs)
        val deployResults: List<AuroraDeployResult> =
                deployFromSpecs(deploymentSpecs, environments, deploy, auroraConfigRefExact)

        val deployer = userDetailsProvider.getAuthenticatedUser().let {
            Deployer(it.fullName ?: it.username, "${it.username}@skatteetaten.no")
        }
        return deployLogService.markRelease(auroraConfigRefExact, deployResults, deployer)

    }

    fun prepareDeployEnvironments(deploymentSpecInternals: List<AuroraDeploymentSpecInternal>): Map<AuroraDeployEnvironment, AuroraDeployResult> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        return deploymentSpecInternals
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



    fun prepareDeployEnvironment(
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
            deploymentSpecInternals: List<AuroraDeploymentSpecInternal>,
            environments: Map<AuroraDeployEnvironment, AuroraDeployResult>,
            deploy: Boolean,
            configRef: AuroraConfigRef
    ): List<AuroraDeployResult> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        return deploymentSpecInternals.map {
            val env = environments[it.environment]
            when {
                env == null -> {
                    if (it.cluster != cluster) {
                        AuroraDeployResult(
                                auroraDeploymentSpecInternal = it,
                                ignored = true,
                                reason = "Not valid in this cluster."
                        )
                    } else {
                        AuroraDeployResult(
                                auroraDeploymentSpecInternal = it,
                                success = false,
                                reason = "Environment was not created."
                        )
                    }
                }
                !env.success -> env.copy(auroraDeploymentSpecInternal = it)
                else -> {
                    try {
                        val result = deployFromSpec(it, deploy, env.projectExist, configRef)
                        result.copy(openShiftResponses = env.openShiftResponses.addIfNotNull(result.openShiftResponses))
                    } catch (e: Exception) {
                        AuroraDeployResult(auroraDeploymentSpecInternal = it, success = false, reason = e.message)
                    }
                }
            }.also {
                logger.info("Deploy done username=${authenticatedUser.username} fullName='${authenticatedUser.fullName}' deployId=${it.deployId} app=${it.auroraDeploymentSpecInternal?.name} namespace=${it.auroraDeploymentSpecInternal?.environment?.namespace} success=${it.success} ignored=${it.ignored} reason=${it.reason}")
            }
        }
    }

    fun deployFromSpec(
            deploymentSpecInternal: AuroraDeploymentSpecInternal,
            shouldDeploy: Boolean,
            namespaceCreated: Boolean,
            auroraConfigRef: AuroraConfigRef
    ): AuroraDeployResult {

        val deployId = UUID.randomUUID().toString().substring(0, 7)

        if (deploymentSpecInternal.cluster != cluster) {
            return AuroraDeployResult(
                    auroraDeploymentSpecInternal = deploymentSpecInternal,
                    ignored = true,
                    reason = "Not valid in this cluster."
            )
        }

        val application = createApplicationDeployment(auroraConfigRef, deploymentSpecInternal, deployId)

        val applicationCommnd = openShiftCommandBuilder.createOpenShiftCommand(
                deploymentSpecInternal.environment.namespace,
                jacksonObjectMapper().convertValue(application)
        )
        val applicationResult =
                openShiftClient.performOpenShiftCommand(deploymentSpecInternal.environment.namespace, applicationCommnd)

        val appResponse: ApplicationDeployment? = applicationResult.responseBody?.let {
            jacksonObjectMapper().convertValue(it)
        }

        if (appResponse == null) {
            return AuroraDeployResult(
                    auroraDeploymentSpecInternal = deploymentSpecInternal,
                    deployId = deployId,
                    openShiftResponses = listOf(applicationResult),
                    success = false,
                    reason = "Creating application object failed"
            )
        }

        val ownerReference = OwnerReferenceBuilder()
                .withApiVersion(appResponse.apiVersion)
                .withKind(appResponse.kind)
                .withName(appResponse.metadata.name)
                .withUid(appResponse.metadata.uid)
                .build()

        logger.debug("Resource provisioning")
        val provisioningResult = resourceProvisioner.provisionResources(deploymentSpecInternal)

        logger.debug("Apply objects")
        val openShiftResponses: List<OpenShiftResponse> = listOf(applicationResult) +
                applyOpenShiftApplicationObjects(
                        deployId, deploymentSpecInternal, provisioningResult, namespaceCreated, ownerReference
                )

        logger.debug("done applying objects")
        val success = openShiftResponses.all { it.success }
        val result = AuroraDeployResult(deploymentSpecInternal, deployId, openShiftResponses, success)
        if (!shouldDeploy) {
            return result.copy(reason = "Deploy explicitly turned of.")
        }

        if (!success) {
            return result.copy(reason = "One or more resources did not complete correctly.")
        }

        if (deploymentSpecInternal.deploy?.flags?.pause == true) {
            return result.copy(reason = "Deployment is paused.")
        }

        val tagResult = deploymentSpecInternal.deploy?.takeIf { it.releaseTo != null }?.let {
            val dockerGroup = it.groupId.dockerGroupSafeName()
            val cmd = TagCommand("$dockerGroup/${it.artifactId}", it.version, it.releaseTo!!, dockerRegistry)
            dockerService.tag(cmd)
        }

        tagResult?.takeIf { !it.success }
                ?.let { return result.copy(tagResponse = it, reason = "Tag command failed") }

        val imageStream = findImageStreamResponse(openShiftResponses)
        val deploymentConfig = findDeploymentConfigResponse(openShiftResponses)
                ?: throw IllegalArgumentException("Missing DeploymentConfig")
        val redeployResult = if (deploymentSpecInternal.type == TemplateType.development) {
            RedeployService.RedeployResult(message = "No deploy was made with ${deploymentSpecInternal.type} type")
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

    fun createApplicationDeployment(
            auroraConfigRef: AuroraConfigRef,
            deploymentSpecInternal: AuroraDeploymentSpecInternal,
            deployId: String
    ): ApplicationDeployment {
        val exactGitRef = auroraConfigService.findExactRef(auroraConfigRef)
        val auroraConfigRefExact = exactGitRef?.let { auroraConfigRef.copy(resolvedRef = it) } ?: auroraConfigRef

        return ApplicationDeployment(
                spec = ApplicationDeploymentSpec(
                        selector = mapOf("name" to deploymentSpecInternal.name),
                        deployTag = deploymentSpecInternal.version,
                        // This is the base shared applicationDeploymentRef
                        applicationId = DigestUtils.sha1Hex(deploymentSpecInternal.appId),
                        applicationDeploymentId = DigestUtils.sha1Hex(deploymentSpecInternal.applicationDeploymentRef.toString()),
                        splunkIndex = deploymentSpecInternal.integration?.splunkIndex,
                        managementPath = deploymentSpecInternal.deploy?.managementPath,
                        releaseTo = deploymentSpecInternal.deploy?.releaseTo,
                        command = ApplicationDeploymentCommand(
                                auroraConfig = auroraConfigRefExact,
                                applicationDeploymentRef = deploymentSpecInternal.applicationDeploymentRef,
                                overrideFiles = deploymentSpecInternal.overrideFiles
                        )
                ),
                metadata = ObjectMetaBuilder()
                        .withName(deploymentSpecInternal.name)
                        .withAnnotations(null)
                        .withLabels(
                                mapOf(
                                        "app" to deploymentSpecInternal.name,
                                        "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
                                        "affiliation" to deploymentSpecInternal.environment.affiliation,
                                        "booberDeployId" to deployId
                                )
                        )
                        .build()
        )
    }

    private fun applyOpenShiftApplicationObjects(
            deployId: String,
            deploymentSpecInternal: AuroraDeploymentSpecInternal,
            provisioningResult: ProvisioningResult? = null,
            mergeWithExistingResource: Boolean,
            ownerReference: OwnerReference
    ): List<OpenShiftResponse> {

        val namespace = deploymentSpecInternal.environment.namespace
        val name = deploymentSpecInternal.name

        val openShiftApplicationResponses: List<OpenShiftResponse> = openShiftCommandBuilder
                .generateApplicationObjects(
                        deployId,
                        deploymentSpecInternal,
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
