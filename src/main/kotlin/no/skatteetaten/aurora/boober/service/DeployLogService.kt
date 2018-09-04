package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.utils.Instants.now
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.springframework.stereotype.Service

@Service
class DeployLogService(
    val bitbucketDeploymentTagService: BitbucketDeploymentTagService,
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
                    val storeResult = storeDeployHistory(deployHistory)
                    it.copy(bitbucketStoreResult = storeResult)
                }
            }
    }

    fun storeDeployHistory(deployHistoryEntry: DeployHistoryEntry): String? {
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
}