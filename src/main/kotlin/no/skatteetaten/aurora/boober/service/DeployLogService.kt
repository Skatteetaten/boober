package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.utils.Instants.now
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException

private val logger = KotlinLogging.logger {}

@Service
class DeployLogService(
    val bitbucketService: BitbucketService,
    val mapper: ObjectMapper,
    @Value("\${integrations.deployLog.git.project}") val project: String,
    @Value("\${integrations.deployLog.git.repo}") val repo: String
) {

    private val DEPLOY_PREFIX = "DEPLOY"

    private val FAILED_PREFIX = "FAILED"

    fun markRelease(
        deployResult: List<AuroraDeployResult>,
        deployer: Deployer
    ): List<AuroraDeployResult> {

        return deployResult
            .map { auroraDeployResult ->
                val result = filterDeployInformation(auroraDeployResult)
                val deployHistory = DeployHistoryEntry(
                    command = result.command,
                    deployer = deployer,
                    time = now,
                    deploymentSpec = result.auroraDeploymentSpecInternal.let {
                        renderSpecAsJson(it, true)
                    },
                    deployId = result.deployId,
                    success = result.success,
                    reason = result.reason ?: "",
                    result = DeployHistoryEntryResult(result.openShiftResponses, result.tagResponse),
                    projectExist = result.projectExist
                )
                try {
                    val storeResult =
                        storeDeployHistory(deployHistory, result.auroraDeploymentSpecInternal.cluster)
                    auroraDeployResult.copy(bitbucketStoreResult = storeResult)
                } catch (e: Exception) {
                    auroraDeployResult.copy(
                        bitbucketStoreResult = e.localizedMessage,
                        reason = auroraDeployResult.reason + " Failed to store deploy result."
                    )
                }
            }
    }

    fun storeDeployHistory(deployHistoryEntry: DeployHistoryEntry, cluster: String): String? {
        val prefix = if (deployHistoryEntry.success) DEPLOY_PREFIX else FAILED_PREFIX
        val message = "$prefix/$cluster-${deployHistoryEntry.command.applicationDeploymentRef}"
        val fileName = "${deployHistoryEntry.command.auroraConfig.name}/${deployHistoryEntry.deployId}.json"
        val content = mapper.writeValueAsString(deployHistoryEntry)
        return bitbucketService.uploadFile(project, repo, fileName, message, content)
    }

    private fun filterDeployInformation(result: AuroraDeployResult): AuroraDeployResult {

        val filteredResponses = result.openShiftResponses.filter { it.responseBody?.openshiftKind != "secret" }
        return result.copy(openShiftResponses = filteredResponses)
    }

    // TODO: test
    fun deployHistory(ref: AuroraConfigRef): List<JsonNode> {
        val files = bitbucketService.getFiles(project, repo, ref.name)
        return files.mapNotNull { bitbucketService.getFile(project, repo, "${ref.name}/$it") }
            .map { mapper.readValue<JsonNode>(it) }
    }

    // TODO: test getting actual file
    fun findDeployResultById(ref: AuroraConfigRef, deployId: String): JsonNode? {
        return try {
            bitbucketService.getFile(project, repo, "${ref.name}/$deployId.json")?.let {
                mapper.readValue(it)
            }
        } catch (e: HttpClientErrorException) {
            logger.warn("Client exception when finding deploy result, status=${e.statusCode} message=\"${e.message}\"")
            if (e.statusCode == HttpStatus.NOT_FOUND) {
                throw DeployLogServiceException("DeployId $deployId was not found for affiliation ${ref.name}")
            } else {
                throw e
            }
        }
    }
}
