package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.runBlocking
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ExternalResourceProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*

@Service
//TODO:Split up. Service is to large
class DeployService(
        val auroraConfigService: AuroraConfigService,
        val openShiftObjectGenerator: OpenShiftObjectGenerator,
        val openShiftClient: OpenShiftClient,
        val dockerService: DockerService,
        val resourceProvisioner: ExternalResourceProvisioner,
        val redeployService: RedeployService,
        val userDetailsProvider: UserDetailsProvider,
        val deployLogService: DeployLogService,
        @Value("\${openshift.cluster}") val cluster: String,
        @Value("\${boober.docker.registry}") val dockerRegistry: String,
        @Value("\${boober.threadpool.namespace:4}") val namespacePoolSize: Int,
        @Value("\${boober.threadpool.app:4}") val appPoolSize: Int) {

    val logger: Logger = LoggerFactory.getLogger(DeployService::class.java)

    val nsDispatcher = newFixedThreadPoolContext(namespacePoolSize, "namespacePool")
    val appDispatcher = newFixedThreadPoolContext(appPoolSize, "appPool")

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


    private fun prepareDeployEnvironments(deploymentSpecs: List<AuroraDeploymentSpec>): Map<AuroraDeployEnvironment, AuroraDeployResult> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()
        return runBlocking(nsDispatcher) {
            deploymentSpecs
                    .filter { it.cluster == cluster }
                    .map { it.environment }
                    .distinct()
                    .map { environment: AuroraDeployEnvironment ->
                        async(nsDispatcher) {

                            if (!authenticatedUser.hasAnyRole(environment.permissions.admin.groups)) {
                                Pair(environment, AuroraDeployResult(success = false, reason = "User=${authenticatedUser.fullName} does not have access to admin this environment from the groups=${environment.permissions.admin.groups}"))
                            }

                            val projectExist = openShiftClient.projectExists(environment.namespace)
                            val environmentResponses = prepareDeployEnvironment(environment, projectExist)

                            val success = environmentResponses.all { it.success }

                            val message = if (!success) {
                                "One or more http calls to OpenShift failed"
                            } else "Namespace created successfully."

                            logger.info("Environment done. user=${authenticatedUser.fullName} namespace=${environment.namespace} success=${success} reason=${message} admins=${environment.permissions.admin.groups} viewers=${environment.permissions.view?.groups}")
                            Pair(environment, AuroraDeployResult(
                                    openShiftResponses = environmentResponses,
                                    success = success,
                                    reason = message,
                                    projectExist = projectExist))
                        }
                    }.map { it.await() }
                    .toMap()
        }
    }

    private fun prepareDeployEnvironment(environment: AuroraDeployEnvironment, projectExist: Boolean): List<OpenShiftResponse> {

        val affiliation = environment.affiliation
        val namespace = environment.namespace

        val createNamespaceResponse = openShiftObjectGenerator.generateProjectRequest(environment).let {
            openShiftClient.createOpenShiftCommand(namespace, it, projectExist)
        }.let { openShiftClient.performOpenShiftCommand(namespace, it) }

        val updateNamespaceResponse = openShiftClient.createUpdateNamespaceCommand(namespace, affiliation).let {
            openShiftClient.performOpenShiftCommand(namespace, it)
        }

        val updateRoleBindingsResponse = openShiftObjectGenerator.generateRolebindings(environment.permissions).map {
            openShiftClient.createOpenShiftCommand(namespace, it, createNamespaceResponse.success, true)
        }.map { openShiftClient.performOpenShiftCommand(namespace, it) }

        return listOf(createNamespaceResponse, updateNamespaceResponse) + updateRoleBindingsResponse
    }

    private fun deployFromSpecs(deploymentSpecs: List<AuroraDeploymentSpec>, environments: Map<AuroraDeployEnvironment, AuroraDeployResult>, deploy: Boolean): List<AuroraDeployResult> {

        val authenticatedUser = userDetailsProvider.getAuthenticatedUser()
        return runBlocking {
            deploymentSpecs.map {
                async(appDispatcher) {

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
                            val result = deployFromSpec(it, deploy, env.projectExist)
                            result.copy(openShiftResponses = env.openShiftResponses.addIfNotNull(result.openShiftResponses))

                        }
                    }.also {
                        logger.info("Deploy done user=${authenticatedUser.fullName} deployId=${it.deployId} app=${it.auroraDeploymentSpec?.name} namespace=${it.auroraDeploymentSpec?.environment?.namespace} success=${it.success} ignored=${it.ignored} reason=${it.reason}")
                    }
                }
            }.map { it.await() }
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
        val redeployResponse = redeployService.triggerRedeploy(deploymentSpec, openShiftResponses)

        val redeploySuccess = if (redeployResponse.isEmpty()) true else redeployResponse.last().success
        val totalSuccess = listOf(success, tagResult?.success, redeploySuccess).filterNotNull().all { it }

        return result.copy(openShiftResponses = openShiftResponses.addIfNotNull(redeployResponse), tagResponse = tagResult, success = totalSuccess, reason = "Deployment success.")
    }

    private fun applyOpenShiftApplicationObjects(deployId: String, deploymentSpec: AuroraDeploymentSpec,
                                                 provisioningResult: ProvisioningResult? = null,
                                                 mergeWithExistingResource: Boolean): List<OpenShiftResponse> {

        val namespace = deploymentSpec.environment.namespace
        val name = deploymentSpec.name

        val openShiftApplicationObjects: List<JsonNode> = openShiftObjectGenerator.generateApplicationObjects(deployId, deploymentSpec, provisioningResult)
        val openShiftApplicationResponses: List<OpenShiftResponse> =
                openShiftApplicationObjects.flatMap {
                    val openShiftCommand = openShiftClient.createOpenShiftCommand(namespace, it, mergeWithExistingResource)
                    if (updateRouteCommandWithChangedHostOrPath(openShiftCommand, deploymentSpec)) {
                        val deleteCommand = openShiftCommand.copy(operationType = OperationType.DELETE)
                        val createCommand = openShiftCommand.copy(operationType = OperationType.CREATE, payload = openShiftCommand.generated!!)
                        listOf(deleteCommand, createCommand)
                    } else {
                        listOf(openShiftCommand)
                    }
                }.map { openShiftClient.performOpenShiftCommand(namespace, it) }

        if (openShiftApplicationResponses.any { !it.success }) {
            logger.warn("One or more commands failed for $namespace/$name. Will not delete objects from previous deploys.")
            return openShiftApplicationResponses
        }

        val deleteOldObjectResponses = openShiftClient
                .createOpenShiftDeleteCommands(name, namespace, deployId)
                .map { openShiftClient.performOpenShiftCommand(namespace, it) }

        return openShiftApplicationResponses.addIfNotNull(deleteOldObjectResponses)
    }


    private fun updateRouteCommandWithChangedHostOrPath(openShiftCommand: OpenshiftCommand, deploymentSpec: AuroraDeploymentSpec): Boolean {

        if (openShiftCommand.payload.openshiftKind != "route") {
            return false
        }

        if (openShiftCommand.operationType != OperationType.UPDATE) {
            return false
        }
        val previous = openShiftCommand.previous!!
        val payload = openShiftCommand.payload


        val hostPointer = "/spec/host"
        val pathPointer = "/spec/path"

        val newHost = payload.at(hostPointer)
        val expectedHost = if (newHost.isMissingNode) {
            deploymentSpec.assembleRouteHost()
        } else {
            newHost.textValue()
        }
        val prevHost = previous.at(hostPointer).textValue()

        val hostChanged = prevHost != expectedHost
        val pathChanged = previous.at(pathPointer) != payload.at(pathPointer)

        val changed = hostChanged || pathChanged

        return changed
    }
}

