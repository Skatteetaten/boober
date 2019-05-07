package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.utils.Instants.now
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException

@Service
class DeployLogService(
    val bitbucketService: BitbucketService,
    val mapper: ObjectMapper,
    @Value("\${boober.bitbucket.tags.project}") val project: String,
    @Value("\${boober.bitbucket.tags.repo}") val repo: String
) {

    val logger: Logger = LoggerFactory.getLogger(DeployLogService::class.java)

    private val DEPLOY_PREFIX = "DEPLOY"

    private val FAILED_PREFIX = "FAILED"

    fun markRelease(
        deployResult: List<AuroraDeployResult>,
        deployer: Deployer
    ): List<AuroraDeployResult> {

        return deployResult
            .map {
                if (it.ignored || it.command == null) {
                    it
                } else {
                    val result = filterDeployInformation(it)
                    val deployHistory = DeployHistoryEntry(
                        command = result.command!!,
                        deployer = deployer,
                        time = now,
                        deploymentSpec = result.auroraDeploymentSpecInternal?.let {
                            renderSpecAsJson(it.spec, true)
                        } ?: mapOf(),
                        deployId = result.deployId,
                        success = result.success,
                        reason = result.reason ?: "",
                        result = DeployHistoryEntryResult(result.openShiftResponses, result.tagResponse),
                        projectExist = result.projectExist
                    )
                    try {
                        val storeResult =
                            storeDeployHistory(deployHistory, result.auroraDeploymentSpecInternal!!.cluster)
                        it.copy(bitbucketStoreResult = storeResult)
                    } catch (e: Exception) {
                        it.copy(
                            bitbucketStoreResult = e.localizedMessage,
                            deployId = "failed",
                            reason = it.reason + " Failed to store deploy result."
                        )
                    }
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

    fun deployHistory(ref: AuroraConfigRef): List<JsonNode> {
        val files = bitbucketService.getFiles(project, repo, ref.name)
        return files.mapNotNull { bitbucketService.getFile(project, repo, "${ref.name}/$it") }
            .map { mapper.readValue<JsonNode>(it) }
    }

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