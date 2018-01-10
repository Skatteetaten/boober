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
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*

@Service
class DeployService(
        val deploymentSpecService: DeploymentSpecService,
        val openShiftObjectGenerator: OpenShiftObjectGenerator,
        val openShiftClient: OpenShiftClient,
        val dockerService: DockerService,
        val resourceProvisioner: ExternalResourceProvisioner,
        val redeployService: RedeployService,
        val deployLogService: DeployLogService,
        @Value("\${openshift.cluster}") val cluster: String,
        @Value("\${boober.docker.registry}") val dockerRegistry: String,
        @Value("\${boober.threadpool.namespace:2}") val namespacePoolSize: Int,
        @Value("\${boober.threadpool.app:2}") val appPoolSize: Int) {

    val logger: Logger = LoggerFactory.getLogger(DeployService::class.java)

    val nsDispatcher = newFixedThreadPoolContext(namespacePoolSize, "namespacePool")
    val appDispatcher = newFixedThreadPoolContext(appPoolSize, "appPool")

    @JvmOverloads
    fun executeDeploy(auroraConfigName: String, applicationIds: List<ApplicationId>, overrides: List<AuroraConfigFile> = listOf(), deploy: Boolean = true): List<AuroraDeployResult> {

        if (applicationIds.isEmpty()) {
            throw IllegalArgumentException("Specify applicationId")
        }

        val deploymentSpecs = deploymentSpecService.createValidatedAuroraDeploymentSpecs(auroraConfigName, applicationIds, overrides)


        val environments = runBlocking(nsDispatcher) {
            deploymentSpecs
                    .filter { it.cluster == cluster }
                    .map { it.environment }
                    .distinct()
                    .map {
                        async(nsDispatcher) {
                            val projectExist = openShiftClient.projectExists(it.namespace)
                            val environmentResponses = prepareDeployEnvironment(it, projectExist)
                            Pair(it, AuroraEnvironmentResult(environmentResponses, projectExist))
                        }
                    }.map { it.await() }
                    .toMap()
        }
        val deployResults: List<AuroraDeployResult> = runBlocking(appDispatcher) {
            deploymentSpecs.map {
                async(appDispatcher) {
                    deployFromSpec(it, deploy, environments[it.environment])
                }
            }.map { it.await() }
        }
        deployLogService.markRelease(auroraConfigName, deployResults)

        return deployResults
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

    fun deployFromSpec(deploymentSpec: AuroraDeploymentSpec, shouldDeploy: Boolean, auroraEnvironmentResult: AuroraEnvironmentResult?): AuroraDeployResult {

        val deployId = UUID.randomUUID().toString()
        //TODO: hasAccessToVolumes
        if (deploymentSpec.cluster != cluster) {
            //TODO: legge med info om at miljø ikke finnes
            return AuroraDeployResult(deployId, deploymentSpec, listOf(), false)
        }

        if (auroraEnvironmentResult == null) {

            //TODO: legge med info om at miljø ikke finnes
            return AuroraDeployResult(deployId, deploymentSpec, listOf(), false)
        }

        logger.debug("Resource provisioning")
        val provisioningResult = resourceProvisioner.provisionResources(deploymentSpec)

        logger.debug("Apply objects")
        val applicationResponses: List<OpenShiftResponse> = applyOpenShiftApplicationObjects(
                deployId, deploymentSpec, provisioningResult, auroraEnvironmentResult.newNamespace)

        val openShiftResponses = auroraEnvironmentResult.openShiftResponses + applicationResponses
        val success = openShiftResponses.all { it.success }
        val result = AuroraDeployResult(deployId, deploymentSpec, openShiftResponses, success)
        if (!shouldDeploy) {
            return result
        }

        if (!success) {
            return result
        }

        if (deploymentSpec.deploy?.flags?.pause == true) {
            return result
        }

        val tagResult = deploymentSpec.deploy?.takeIf { it.releaseTo != null }?.let {
            val dockerGroup = it.groupId.dockerGroupSafeName()
            val cmd = TagCommand("$dockerGroup/${it.artifactId}", it.version, it.releaseTo!!, dockerRegistry)
            dockerService.tag(cmd)
        }
        val redeployResponse = redeployService.triggerRedeploy(deploymentSpec, openShiftResponses)

        val redeploySuccess = if (redeployResponse.isEmpty()) true else redeployResponse.last().success
        val totalSuccess = listOf(success, tagResult?.success, redeploySuccess).filterNotNull().all { it }

        return result.copy(openShiftResponses = openShiftResponses.addIfNotNull(redeployResponse), tagResponse = tagResult, success = totalSuccess)
    }

    private fun applyOpenShiftApplicationObjects(deployId: String, deploymentSpec: AuroraDeploymentSpec,
                                                 provisioningResult: ProvisioningResult? = null,
                                                 mergeWithExistingResource: Boolean): List<OpenShiftResponse> {

        val namespace = deploymentSpec.environment.namespace
        val name = deploymentSpec.name

        val openShiftApplicationObjects: List<JsonNode> = openShiftObjectGenerator.generateApplicationObjects(deployId, deploymentSpec, provisioningResult)
        val openShiftApplicationResponses: List<OpenShiftResponse> =

                runBlocking(appDispatcher) {
                    openShiftApplicationObjects.flatMap {
                        val openShiftCommand = openShiftClient.createOpenShiftCommand(namespace, it, mergeWithExistingResource)
                        if (updateRouteCommandWithChangedHostOrPath(openShiftCommand, deploymentSpec)) {
                            val deleteCommand = openShiftCommand.copy(operationType = OperationType.DELETE)
                            val createCommand = openShiftCommand.copy(operationType = OperationType.CREATE, payload = openShiftCommand.generated!!)
                            listOf(deleteCommand, createCommand)
                        } else {
                            listOf(openShiftCommand)
                        }
                    }.map {
                        async(appDispatcher) {
                            openShiftClient.performOpenShiftCommand(namespace, it)
                        }
                    }.map { it.await() }
                }

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

