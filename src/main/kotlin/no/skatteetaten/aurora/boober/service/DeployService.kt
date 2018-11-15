package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.describeString
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ExternalResourceProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
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
    val openShiftCommandBuilder: OpenShiftCommandService,
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
            auroraConfigService.createValidatedAuroraDeploymentSpecs(
                auroraConfigRefExact,
                applicationDeploymentRefs,
                overrides
            )
        val environments = prepareDeployEnvironments(deploymentSpecs)
        val deployResults: List<AuroraDeployResult> =
            deployFromSpecs(deploymentSpecs, environments, deploy, auroraConfigRefExact)

        val deployer = userDetailsProvider.getAuthenticatedUser().let {
            Deployer(it.fullName ?: it.username, "${it.username}@skatteetaten.no")
        }
        return deployLogService.markRelease(deployResults, deployer)
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

            val cmd = ApplicationDeploymentCommand(
                auroraConfig = configRef,
                applicationDeploymentRef = it.applicationDeploymentRef,
                overrideFiles = it.overrideFiles
            )

            val env = environments[it.environment]
            when {
                env == null -> {
                    if (it.cluster != cluster) {
                        AuroraDeployResult(
                            auroraDeploymentSpecInternal = it,
                            ignored = true,
                            reason = "Not valid in this cluster.",
                            command = cmd
                        )
                    } else {
                        AuroraDeployResult(
                            auroraDeploymentSpecInternal = it,
                            success = false,
                            reason = "Environment was not created.",
                            command = cmd
                        )
                    }
                }
                !env.success -> env.copy(auroraDeploymentSpecInternal = it)
                else -> {
                    try {
                        val result = deployFromSpec(it, deploy, env.projectExist, configRef, cmd)
                        result.copy(openShiftResponses = env.openShiftResponses.addIfNotNull(result.openShiftResponses))
                    } catch (e: Exception) {
                        AuroraDeployResult(
                            auroraDeploymentSpecInternal = it,
                            success = false,
                            reason = e.message,
                            command = cmd
                        )
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
        auroraConfigRef: AuroraConfigRef,
        cmd: ApplicationDeploymentCommand
    ): AuroraDeployResult {

        val deployId = UUID.randomUUID().toString().substring(0, 7)

        if (deploymentSpecInternal.cluster != cluster) {
            return AuroraDeployResult(
                auroraDeploymentSpecInternal = deploymentSpecInternal,
                ignored = true,
                reason = "Not valid in this cluster.",
                command = cmd
            )
        }

        val application = createApplicationDeployment(deploymentSpecInternal, deployId, cmd)

        val applicationCommand = openShiftCommandBuilder.createOpenShiftCommand(
            deploymentSpecInternal.environment.namespace,
            jacksonObjectMapper().convertValue(application)
        )

        val applicationResult =
            openShiftClient.performOpenShiftCommand(deploymentSpecInternal.environment.namespace, applicationCommand)

        val appResponse: ApplicationDeployment? = applicationResult.responseBody?.let {
            jacksonObjectMapper().convertValue(it)
        }

        if (appResponse == null) {
            return AuroraDeployResult(
                auroraDeploymentSpecInternal = deploymentSpecInternal,
                deployId = deployId,
                openShiftResponses = listOf(applicationResult),
                success = false,
                reason = "Creating application object failed",
                command = cmd
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
        val result = AuroraDeployResult(cmd, deploymentSpecInternal, deployId, openShiftResponses, success)

        if (!success) {
            val failedCommands = openShiftResponses.filter { !it.success }.describeString()
            return result.copy(reason = "Errors $failedCommands")
        }

        if (!shouldDeploy) {
            return result.copy(reason = "No deploy made, turned off in payload.")
        }

        if (deploymentSpecInternal.deploy?.flags?.pause == true) {
            return result.copy(reason = "Deployment is paused and will be/remain scaled down.")
        }

        val tagResult = deploymentSpecInternal.deploy?.takeIf { it.releaseTo != null }?.let {
            val dockerGroup = it.groupId.dockerGroupSafeName()
            dockerService.tag(TagCommand("$dockerGroup/${it.artifactId}", it.version, it.releaseTo!!, dockerRegistry))
        }

        tagResult?.takeIf { !it.success }
            ?.let { return result.copy(tagResponse = it, reason = "Tag command failed.") }

        val redeployResult = redeployService.triggerRedeploy(openShiftResponses, deploymentSpecInternal.type)

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

    fun createApplicationDeployment(
        deploymentSpecInternal: AuroraDeploymentSpecInternal,
        deployId: String,
        cmd: ApplicationDeploymentCommand
    ): ApplicationDeployment {

        val ttl = deploymentSpecInternal.deploy?.ttl?.let {
            val removeInstant = Instants.now + it
            "removeAfter" to removeInstant.epochSecond.toString()
        }
        val applicationId = DigestUtils.sha1Hex(deploymentSpecInternal.appId)
        val applicationDeploymentId = DigestUtils.sha1Hex(deploymentSpecInternal.appDeploymentId)
        return ApplicationDeployment(
            spec = ApplicationDeploymentSpec(
                selector = mapOf("name" to deploymentSpecInternal.name),
                deployTag = deploymentSpecInternal.version,
                applicationId = applicationId,
                applicationDeploymentId = applicationDeploymentId,
                applicationName = deploymentSpecInternal.appName,
                applicationDeploymentName = deploymentSpecInternal.name,
                splunkIndex = deploymentSpecInternal.integration?.splunkIndex,
                managementPath = deploymentSpecInternal.deploy?.managementPath,
                releaseTo = deploymentSpecInternal.deploy?.releaseTo,
                command = cmd
            ),
            metadata = ObjectMetaBuilder()
                .withName(deploymentSpecInternal.name)
                .withLabels(
                    mapOf(
                        "app" to deploymentSpecInternal.name,
                        "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
                        "affiliation" to deploymentSpecInternal.environment.affiliation,
                        "booberDeployId" to deployId,
                        "applicationId" to applicationId,
                        "id" to applicationDeploymentId
                    ).addIfNotNull(ttl)
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

        val objects = openShiftCommandBuilder.generateOpenshiftObjects(
            deployId,
            deploymentSpecInternal,
            provisioningResult,
            mergeWithExistingResource,
            ownerReference
        )

        val openShiftApplicationResponses: List<OpenShiftResponse> = objects.flatMap {
            openShiftCommandBuilder.createAndApplyObjects(namespace, it, mergeWithExistingResource)
        }

        if (openShiftApplicationResponses.any { !it.success }) {
            logger.warn("One or more commands failed for $namespace/$name. Will not delete objects from previous deploys.")
            return openShiftApplicationResponses
        }

        val deleteOldObjectResponses = openShiftCommandBuilder
            .createOpenShiftDeleteCommands(name, namespace, deployId)
            .map { openShiftClient.performOpenShiftCommand(namespace, it) }

        return openShiftApplicationResponses.addIfNotNull(deleteOldObjectResponses)
    }
}
