package no.skatteetaten.aurora.boober.service

import io.fabric8.kubernetes.api.model.OwnerReference
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.whenFalse
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
    val redeployService: RedeployService,
    val userDetailsProvider: UserDetailsProvider,
    val deployLogService: DeployLogService,
    @Value("\${openshift.cluster}") val cluster: String,
    @Value("\${integrations.docker.registry}") val dockerRegistry: String
) {

    /* TOOD: Fix
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
            auroraConfigService.createValidatedAuroraDeploymentContexts(
                auroraConfigRefExact,
                applicationDeploymentRefs,
                overrides
            )

        val usedOverrideNames: List<String> =
            deploymentSpecs.flatMap { spec -> spec.applicationFiles.filter { it.override } }.map { it.configName }

        val unusedOverrides = overrides.filter { !usedOverrideNames.contains(it.configName) }
        if (unusedOverrides.isNotEmpty()) {
            val overrideString = unusedOverrides.joinToString(",") { it.name }
            val refString = applicationDeploymentRefs.joinToString(",")
            throw IllegalArgumentException(
                "Overrides files '$overrideString' does not apply to any deploymentReference ($refString)"
            )
        }

        val environments = prepareDeployEnvironments(deploymentSpecs)
        val deployResults: List<AuroraDeployResult> =
            deployFromSpecs(deploymentSpecs, environments, deploy, auroraConfigRefExact)

        val deployer = userDetailsProvider.getAuthenticatedUser().let {
            Deployer(it.fullName ?: it.username, "${it.username}@skatteetaten.no")
        }
        return deployLogService.markRelease(deployResults, deployer)
        return emptyList();
    }

    fun prepareDeployEnvironments(deploymentSpecInternals: List<AuroraDeploymentContext>): Map<AuroraDeployEnvironment, AuroraDeployResult> {

        return deploymentSpecInternals
            .filter { it.cluster == cluster }
            .map { it.environment }
            .distinct()
            .associate(this::prepareDeployEnvironment)
    }

    fun prepareDeployEnvironment(environment: AuroraDeployEnvironment): Pair<AuroraDeployEnvironment, AuroraDeployResult> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()
        val userNotInAdminUsers = !environment.permissions.admin.users.contains(authenticatedUser.username)
        val userNotInAnyAdminGroups = !authenticatedUser.hasAnyRole(environment.permissions.admin.groups)

        if (userNotInAdminUsers && userNotInAnyAdminGroups) {
            return Pair(
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

        return Pair(
            environment, AuroraDeployResult(
                openShiftResponses = environmentResponses,
                success = success,
                reason = message,
                projectExist = projectExist
            )
        )
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
            deploymentSpecInternals: List<AuroraDeploymentContext>,
            environments: Map<AuroraDeployEnvironment, AuroraDeployResult>,
            deploy: Boolean,
            configRef: AuroraConfigRef
    ): List<AuroraDeployResult> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()

        return emptyList()
    return deploymentSpecInternals.map {

        val cmd = ApplicationDeploymentCommand(
            auroraConfig = configRef,
            applicationDeploymentRef = it.adr,
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

    fun deployFromSpec(
        deploymentSpecInternal: AuroraDeploymentContext,
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

        logger.debug("Resource provisioning")
        val provisioningResult = resourceProvisioner.provisionResources(deploymentSpecInternal)

        val dbhSchemas = provisioningResult.schemaProvisionResults?.results?.map { it.dbhSchema } ?: listOf()
        val provisions = Provisions(dbhSchemas)

        val updateBy = userDetailsProvider.getAuthenticatedUser().username.replace(":", "-")
        val application =
            ApplicationDeploymentGenerator.generate(deploymentSpecInternal, deployId, cmd, updateBy, provisions)

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

        val tagResult = deploymentSpecInternal.deploy?.takeIf { it.releaseTo != null }?.let {
            val dockerGroup = it.groupId.dockerGroupSafeName()
            dockerService.tag(TagCommand("$dockerGroup/${it.artifactId}", it.version, it.releaseTo!!, dockerRegistry))
        }
        val rawResult = AuroraDeployResult(
            command = cmd,
            deployId = deployId,
            auroraDeploymentSpecInternal = deploymentSpecInternal,
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
                deployId, deploymentSpecInternal, provisioningResult, namespaceCreated, ownerReference
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

        if (deploymentSpecInternal.deploy?.flags?.pause == true) {
            return result.copy(reason = "Deployment is paused and will be/remain scaled down.")
        }

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
 */
}
