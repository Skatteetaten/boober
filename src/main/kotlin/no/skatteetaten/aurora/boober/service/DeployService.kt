package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.dockerGroupSafeName
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.eclipse.jgit.api.Git
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*

data class DeployParams(
        val envs: List<String> = listOf(),
        val apps: List<String> = listOf(),
        val overrides: MutableList<AuroraConfigFile> = mutableListOf(),
        val deploy: Boolean
) {
    val applicationIds: List<ApplicationId>
        get() = envs.flatMap { env -> apps.map { app -> ApplicationId(env, app) } }
}


@Service
class DeployService(
        val openShiftObjectGenerator: OpenShiftObjectGenerator,
        val openShiftClient: OpenShiftClient,
        val gitService: GitService,
        val deployBundleService: DeployBundleService,
        val permissionService: PermissionService,
        val mapper: ObjectMapper,
        val dockerService: DockerService,
        val resourceProvisioner: ExternalResourceProvisioner,
        val redeployService: RedeployService,
        @Value("\${openshift.cluster}") val cluster: String,
        @Value("\${boober.docker.registry}") val dockerRegistry: String) {

    val logger: Logger = LoggerFactory.getLogger(DeployService::class.java)

    private val DEPLOY_PREFIX = "DEPLOY"
    private val FAILED_PREFIX = "FAILED"


    @JvmOverloads
    fun executeDeploy(affiliation: String, applicationIds: List<ApplicationId>, overrides: List<AuroraConfigFile> = mutableListOf(), deploy: Boolean = true): List<AuroraDeployResult> {

        if (applicationIds.isEmpty()) {
            throw IllegalArgumentException("Specify applicationId")
        }

        return deployBundleService.withDeployBundle(affiliation, overrides) { repo, it ->
            logger.debug("deploy")
            logger.debug("create deployment spec")

            val deploymentSpecs: List<AuroraDeploymentSpec> = deployBundleService.createAuroraDeploymentSpecs(it, applicationIds)
            val deployResults: List<AuroraDeployResult> = deploymentSpecs
                    .filter { it.cluster == cluster }
                    .filter { hasAccessToAllVolumes(it.volume) }
                    .map {
                        logger.debug("deploy from spec")
                        val res = deployFromSpec(it, deploy)
                        logger.debug("/deploy from spec")
                        res
                    }
            logger.debug("mark release")
            markRelease(deployResults, repo)
            logger.debug("/mark release")
            logger.debug("/deploy")
            deployResults
        }
    }

    fun deployFromSpec(deploymentSpec: AuroraDeploymentSpec, shouldDeploy: Boolean): AuroraDeployResult {

        val deployId = UUID.randomUUID().toString()

        logger.debug("Resource provisioning")
        val provisioningResult = resourceProvisioner.provisionResources(deploymentSpec)

        logger.debug("Project exist")
        val projectExist = openShiftClient.projectExists(deploymentSpec.namespace)
        logger.debug("Prepare environment")
        val environmentResponses = prepareDeployEnvironment(deploymentSpec, projectExist)
        logger.debug("Apply objects")
        val applicationResponses: List<OpenShiftResponse> = applyOpenShiftApplicationObjects(deployId, deploymentSpec, provisioningResult, projectExist)

        val openShiftResponses = environmentResponses + applicationResponses
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
        logger.debug("Redeploy")
        val redeployResponse = redeployService.triggerRedeploy(deploymentSpec, openShiftResponses)

        val redeploySuccess = if (redeployResponse.isEmpty()) true else redeployResponse.last().success
        val totalSuccess = listOf(success, tagResult?.success, redeploySuccess).filterNotNull().all { it }

        return result.copy(openShiftResponses = openShiftResponses.addIfNotNull(redeployResponse), tagResponse = tagResult, success = totalSuccess)
    }

    private fun applyOpenShiftApplicationObjects(deployId: String, deploymentSpec: AuroraDeploymentSpec,
                                                 provisioningResult: ProvisioningResult? = null,
                                                 mergeWithExistingResource: Boolean): List<OpenShiftResponse> {

        val namespace = deploymentSpec.namespace
        val name = deploymentSpec.name

        val openShiftApplicationObjects: List<JsonNode> = openShiftObjectGenerator.generateApplicationObjects(deployId, deploymentSpec, provisioningResult)
        val openShiftApplicationResponses: List<OpenShiftResponse> = openShiftApplicationObjects.flatMap {
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

    private fun prepareDeployEnvironment(deploymentSpec: AuroraDeploymentSpec, projectExist: Boolean): List<OpenShiftResponse> {

        val affiliation = deploymentSpec.affiliation
        val namespace = deploymentSpec.namespace

        val createNamespaceResponse = openShiftObjectGenerator.generateProjectRequest(deploymentSpec).let {
            openShiftClient.createOpenShiftCommand(namespace, it, projectExist)
        }.let { openShiftClient.performOpenShiftCommand(namespace, it) }

        val updateNamespaceResponse = openShiftClient.createUpdateNamespaceCommand(namespace, affiliation).let {
            openShiftClient.performOpenShiftCommand(namespace, it)
        }

        val updateRoleBindingsResponse = openShiftObjectGenerator.generateRolebindings(deploymentSpec.permissions).map {
            openShiftClient.createOpenShiftCommand(namespace, it, createNamespaceResponse.success, true)
        }.map { openShiftClient.performOpenShiftCommand(namespace, it) }

        return listOf(createNamespaceResponse, updateNamespaceResponse) + updateRoleBindingsResponse
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

    private fun markRelease(res: List<AuroraDeployResult>, repo: Git) {

        val refs = res.map {
            val result = filterSensitiveInformation(it)
            val prefix = if (it.success) {
                DEPLOY_PREFIX
            } else {
                FAILED_PREFIX
            }
            gitService.markRelease(repo, "$prefix/${it.tag}", mapper.writeValueAsString(result))
        }
        gitService.pushTags(repo, refs)
    }

    fun hasAccessToAllVolumes(volume: AuroraVolume?): Boolean {
        if (volume == null) return true

        val secretAccess = permissionService.hasUserAccess(volume.permissions)

        val mountsWithNoPermissions = volume.mounts?.filter {
            !permissionService.hasUserAccess(it.permissions)
        } ?: emptyList()
        val volumeAccess = mountsWithNoPermissions.isEmpty()

        return secretAccess && volumeAccess

    }

    private fun filterSensitiveInformation(result: AuroraDeployResult): AuroraDeployResult {

        val filteredResponses = result.openShiftResponses.filter { it.responseBody?.get("kind")?.asText() != "Secret" }
        return result.copy(openShiftResponses = filteredResponses)
    }

    fun deployHistory(affiliation: String): List<DeployHistory> {
        val repo = gitService.checkoutRepoForAffiliation(affiliation)
        val res = gitService.tagHistory(repo)
                .filter { it.tagName.startsWith(DEPLOY_PREFIX) }
                .map { DeployHistory(it.taggerIdent, mapper.readTree(it.fullMessage)) }
        gitService.closeRepository(repo)
        return res
    }

    fun findDeployResultById(auroraConfigId: String, deployId: String): DeployHistory? {
        val repo = gitService.checkoutRepoForAffiliation(auroraConfigId)
        val res: DeployHistory? = gitService.tagHistory(repo)
                .firstOrNull { it.tagName.endsWith(deployId) }
                ?.let { DeployHistory(it.taggerIdent, mapper.readTree(it.fullMessage)) }
        gitService.closeRepository(repo)
        return res
    }
}

