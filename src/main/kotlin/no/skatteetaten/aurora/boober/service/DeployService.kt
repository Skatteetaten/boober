package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraVolume
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.TemplateType.build
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.service.internal.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.internal.DeployHistory
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.eclipse.jgit.api.Git
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
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
        val secretVaultPermissionService: SecretVaultPermissionService,
        val mapper: ObjectMapper,
        val dockerService: DockerService,
        @Value("\${openshift.cluster}") val cluster: String,
        @Value("\${boober.docker.registry}") val dockerRegistry: String) {

    val logger: Logger = LoggerFactory.getLogger(DeployService::class.java)

    private val DEPLOY_PREFIX = "DEPLOY"
    private val FAILED_PREFIX = "FAILED"


    fun executeDeploy(affiliation: String, deployParams: DeployParams): List<AuroraDeployResult> {

        val applicationIds = deployParams.applicationIds
        if (applicationIds.isEmpty()) {
            throw IllegalArgumentException("Specify applicationId")
        }

        return deployBundleService.withDeployBundle(affiliation, deployParams.overrides, {
            val deploymentSpecs: List<AuroraDeploymentSpec> = deployBundleService.createAuroraDeploymentSpecs(it, applicationIds)
            val deployResults: List<AuroraDeployResult> = deploymentSpecs
                    .filter { it.cluster == cluster }
                    .filter { hasAccessToAllVolumes(it.volume) }
                    .map { deployFromSpec(it, deployParams.deploy) }
            markRelease(deployResults, it.repo)
            deployResults
        })
    }

    fun deployFromSpec(deploymentSpec: AuroraDeploymentSpec, shouldDeploy: Boolean): AuroraDeployResult {

        val deployId = UUID.randomUUID().toString()

        val openShiftResponses: List<OpenShiftResponse> = performOpenShiftCommands(deployId, deploymentSpec, shouldDeploy)
        val redeployResponse: ResponseEntity<JsonNode>? = if (shouldDeploy) triggerRedeploy(deploymentSpec, openShiftResponses) else null

        val success = openShiftResponses.all { it.success }
        return AuroraDeployResult(deployId, deploymentSpec, openShiftResponses, redeployResponse, success)
    }

    private fun performOpenShiftCommands(deployId: String, deploymentSpec: AuroraDeploymentSpec, shouldDeploy: Boolean): List<OpenShiftResponse> {

        val affiliation = deploymentSpec.affiliation

        val namespace = deploymentSpec.namespace
        val name = deploymentSpec.name

        val generateProjectResponses = openShiftObjectGenerator.generateProject(deploymentSpec).let {
            val createCommand = openShiftClient.createOpenShiftCommand(namespace, it)
            val createResponse = openShiftClient.performOpenShiftCommand(namespace, createCommand)
            val updateNamespaceCommand = openShiftClient.createUpdateNamespaceCommand(namespace, affiliation)
            val updateNamespaceResponse = openShiftClient.performOpenShiftCommand(namespace, updateNamespaceCommand)

            listOf(createResponse, updateNamespaceResponse)
        }

        val openShiftApplicationObjects: List<JsonNode> = openShiftObjectGenerator.generateApplicationObjects(deploymentSpec, deployId)
        val openShiftApplicationResponses: List<OpenShiftResponse> = openShiftApplicationObjects.map {
            val openShiftCommand = openShiftClient.createOpenShiftCommand(namespace, it)
            openShiftClient.performOpenShiftCommand(namespace, openShiftCommand)
        }

        if (openShiftApplicationResponses.any { !it.success }) {
            logger.warn("One or more commands failed for $namespace/$name")
            return openShiftApplicationResponses
        }

        val deleteOldObjectResponses = openShiftClient
                .createOpenShiftDeleteCommands(name, namespace, deployId)
                .map { openShiftClient.performOpenShiftCommand(namespace, it) }

        return generateProjectResponses.addIfNotNull(openShiftApplicationResponses).addIfNotNull(deleteOldObjectResponses)
    }

    private fun triggerRedeploy(deploymentSpec: AuroraDeploymentSpec, openShiftResponses: List<OpenShiftResponse>): ResponseEntity<JsonNode>? {

        val tagCommand = deploymentSpec.deploy?.takeIf { it.releaseTo != null }?.let {
            val dockerGroup = it.groupId.replace(".", "_")
            TagCommand("${dockerGroup}/${it.artifactId}", it.version, it.releaseTo!!, dockerRegistry)
        }

        val tagCommandResponse = tagCommand?.let { dockerService.tag(it) }

        val namespace = deploymentSpec.namespace
        generateRedeployResourceFromSpec(deploymentSpec, openShiftResponses)
                ?.let { openShiftClient.createOpenShiftCommand(namespace, it) }
                ?.let { openShiftClient.performOpenShiftCommand(namespace, it) }
        return tagCommandResponse
    }

    private fun markRelease(res: List<AuroraDeployResult>, repo: Git) {

        res.forEach {

            //TODO: MARK FAILURE
            val result = filterSensitiveInformation(it)
            val prefix = if (it.success) {
                DEPLOY_PREFIX
            } else {
                FAILED_PREFIX
            }
            gitService.markRelease(repo, "$prefix/${it.tag}", mapper.writeValueAsString(result))
        }

        gitService.push(repo)
    }

    fun hasAccessToAllVolumes(volume: AuroraVolume?): Boolean {
        if (volume == null) return true

        val secretAccess = secretVaultPermissionService.hasUserAccess(volume.permissions)

        val mountsWithNoPermissions = volume.mounts?.filter {
            !secretVaultPermissionService.hasUserAccess(it.permissions)
        } ?: emptyList()
        val volumeAccess = mountsWithNoPermissions.isEmpty()

        //TODO: Her må vi finne ut hva vi skal returnere for å vise at disse applikasjonen ikke blir deployet fordi man ikke har tilgang
        return secretAccess && volumeAccess

    }

    private fun filterSensitiveInformation(result: AuroraDeployResult): AuroraDeployResult {

        val filteredResponses = result.openShiftResponses.filter { it.responseBody?.get("kind")?.asText() != "Secret" }
        return result.copy(openShiftResponses = filteredResponses)
    }


    fun generateRedeployResourceFromSpec(deploymentSpec: AuroraDeploymentSpec, openShiftResponses: List<OpenShiftResponse>): JsonNode? {

        val type: TemplateType = deploymentSpec.type
        val name: String = deploymentSpec.name
        val dockerImage: String? = deploymentSpec.deploy?.dockerImage

        return generateRedeployResource(type, name, dockerImage, openShiftResponses)

    }

    fun generateRedeployResource(type: TemplateType, name: String, dockerImage: String?, openShiftResponses: List<OpenShiftResponse>): JsonNode? {
        if (type == build) {
            return null
        }

        if (type == development) {

            return openShiftResponses.filter { it.responseBody?.openshiftKind ?: "" == "buildconfig" }
                    .find { it.command.operationType != OperationType.CREATE }
                    ?.let { openShiftObjectGenerator.generateBuildRequest(name) }

        }

        val imageStream = openShiftResponses.find { it.responseBody?.get("kind")?.asText()?.toLowerCase() ?: "" == "imagestream" }
        val deployment = openShiftResponses.find { it.responseBody?.get("kind")?.asText()?.toLowerCase() ?: "" == "deploymentconfig" }

        imageStream?.takeIf { !it.labelChanged("releasedVersion") && it.command.operationType == OperationType.UPDATE }?.let {
            return openShiftObjectGenerator.generateDeploymentRequest(name)
        }

        if (imageStream == null) {
            return deployment?.let {
                openShiftObjectGenerator.generateDeploymentRequest(name)
            }
        }
        if (dockerImage == null) {
            throw NullPointerException("DockerImage must be set")
        }
        return openShiftObjectGenerator.generateImageStreamImport(name, dockerImage)
    }


    fun deployHistory(affiliation: String): List<DeployHistory> {
        val repo = gitService.checkoutRepoForAffiliation(affiliation)
        val res = gitService.tagHistory(repo)
                .filter { it.tagName.startsWith(DEPLOY_PREFIX) }
                .map { DeployHistory(it.taggerIdent, mapper.readTree(it.fullMessage)) }
        gitService.closeRepository(repo)
        return res
    }
}
