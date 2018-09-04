package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.utils.Instants.now
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.springframework.stereotype.Service

@Service
class DeployLogService(
    val bitbucketDeploymentTagService: BitbucketDeploymentTagService,
    val gitService: GitService,
    val mapper: ObjectMapper
) {

    private val DEPLOY_PREFIX = "DEPLOY"

    private val FAILED_PREFIX = "FAILED"

    fun markRelease(
        ref: AuroraConfigRef,
        deployResult: List<AuroraDeployResult>,
        deployer: Deployer
    ): List<AuroraDeployResult> {

        return deployResult
            .map {
                if (it.ignored) {
                    it
                } else {
                    val result = filterDeployInformation(it)
                    val deployHistory = DeployHistoryEntry(
                        command = result.command,
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
                    val storeResult = storeDeployHistory(deployHistory)
                    it.copy(bitbucketStoreResult = storeResult)
                }
            }
    }

    fun storeDeployHistory(deployHistoryEntry: DeployHistoryEntry): JsonNode? {
        val prefix = if (deployHistoryEntry.success) DEPLOY_PREFIX else FAILED_PREFIX
        val message = "$prefix/${deployHistoryEntry.command.applicationDeploymentRef}"
        val fileName = "${deployHistoryEntry.command.auroraConfig.name}/${deployHistoryEntry.deployId}.json"
        val content = mapper.writeValueAsString(deployHistoryEntry)
        return bitbucketDeploymentTagService.uploadFile(fileName, message, content)
    }

    private fun filterDeployInformation(result: AuroraDeployResult): AuroraDeployResult {

        val filteredResponses = result.openShiftResponses.filter { it.responseBody?.openshiftKind != "secret" }
        return result.copy(openShiftResponses = filteredResponses)
    }

    fun deployHistory(ref: AuroraConfigRef): List<DeployHistoryEntry> {
        val files = bitbucketDeploymentTagService.getFiles(ref.name)
        return files.mapNotNull { bitbucketDeploymentTagService.getFile<DeployHistoryEntry>(it) }
            .filter { it.success }
    }

    fun findDeployResultById(ref: AuroraConfigRef, deployId: String): DeployHistoryEntry? {
        return bitbucketDeploymentTagService.getFile("${ref.name}/$deployId.json")
    }

    fun getAllTags(ref: AuroraConfigRef): List<DeployHistoryEntry> {
        val repo = gitService.checkoutRepository(ref.name, refName = ref.refName)
        val res = gitService.getTagHistory(repo)
            .map {
                val success = it.tagName.startsWith(DEPLOY_PREFIX)
                val resolvedRef = it.`object`.id.abbreviate(8).name()
                val fullMessage = it.fullMessage
                val jsonNode: JsonNode = mapper.readValue(fullMessage)
                val resolvedAuroraConfigRef = ref.copy(resolvedRef = resolvedRef)

                // TODO: this might be different when we are new
                val rawSpec = jsonNode.at("/result/auroraDeploymentSpec/fields") as ObjectNode


                it.taggerIdent.let {
                    DeployHistoryEntry(
                        version = "v1",
                        deployer = Deployer(it.name, it.emailAddress),
                        time = it.`when`.toInstant(),
                        success = success,
                        reason = jsonNode.at("/reason").asText(),
                        deployId = jsonNode.get("/result/deployId").asText(),
                        deploymentSpec = mapper.

                    )
                }
            }
        repo.close()
        return res
    }
}