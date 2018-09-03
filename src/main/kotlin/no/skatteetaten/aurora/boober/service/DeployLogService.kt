package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.utils.Instants.now
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.springframework.stereotype.Service

@Service
class DeployLogService(val bitbucketDeploymentTagService: BitbucketDeploymentTagService, val gitService: GitService, val mapper: ObjectMapper) {

    private val DEPLOY_PREFIX = "DEPLOY"

    private val FAILED_PREFIX = "FAILED"

    fun markRelease(ref: AuroraConfigRef, deployResult: List<AuroraDeployResult>, deployer: Deployer): List<AuroraDeployResult> {

        return deployResult
                .filter { !it.ignored }
                .map {
                    val result = filterDeployInformation(it)
                    val deployHistory = DeployHistory(deployer, now, result, ref)
                    val storeResult = storeDeployHistory(deployHistory)
                    it.copy(bitbucketStoreResult = storeResult)
                }
    }

    fun storeDeployHistory(deployHistory: DeployHistory): JsonNode? {
        val prefix = if (deployHistory.result.success) DEPLOY_PREFIX else FAILED_PREFIX
        val message = "$prefix/${deployHistory.result.tag}"
        val fileName = "${deployHistory.ref.name}/${deployHistory.result.deployId}.json"
        val content = mapper.writeValueAsString(deployHistory)
        return bitbucketDeploymentTagService.uploadFile(fileName, message, content)
    }

    private fun filterDeployInformation(result: AuroraDeployResult): AuroraDeployResult {

        val filteredResponses = result.openShiftResponses.filter { it.responseBody?.openshiftKind != "secret" }
        return result.copy(openShiftResponses = filteredResponses)
    }

    fun deployHistory(ref: AuroraConfigRef): List<DeployHistory> {
        val files = bitbucketDeploymentTagService.getFiles(ref.name)
        return files.mapNotNull { bitbucketDeploymentTagService.getFile<DeployHistory>(it) }
                .filter { it.result.success }
    }

    fun findDeployResultById(ref: AuroraConfigRef, deployId: String): DeployHistory? {
        return bitbucketDeploymentTagService.getFile("${ref.name}/$deployId.json")

    }

    fun getAllTags(ref: AuroraConfigRef): List<DeployHistory> {
        val repo = gitService.checkoutRepository(ref.name, refName = ref.refName)
        val res = gitService.getTagHistory(repo)
                .map {
                    val resolvedRef = it.`object`.id.abbreviate(8).name()
                    val fullMessage = it.fullMessage
                    it.taggerIdent.let {
                        DeployHistory(
                                Deployer(it.name, it.emailAddress),
                                it.`when`.toInstant(),
                                mapper.readValue(fullMessage),
                                ref.copy(resolvedRef = resolvedRef)
                        )
                    }
                }
        repo.close()
        return res
    }

}