package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

@Service
class DeployLogService(val bitbucketDeploymentTagService: BitbucketDeploymentTagService, val mapper: ObjectMapper) {

    private val DEPLOY_PREFIX = "DEPLOY"

    private val FAILED_PREFIX = "FAILED"

    fun markRelease(ref: AuroraConfigRef, deployResult: List<AuroraDeployResult>) {

        deployResult
            .filter { !it.ignored }
            .map {
                val result = filterSensitiveInformation(it)
                val prefix = if (it.success) DEPLOY_PREFIX else FAILED_PREFIX
                val message = "$prefix/${it.tag}"
                val fileName = "${ref.name}/${it.deployId}.json"
                val content = mapper.writeValueAsString(result)
                bitbucketDeploymentTagService.postDeployResult(fileName, message, content)
            }
    }

    private fun filterSensitiveInformation(result: AuroraDeployResult): AuroraDeployResult {

        val filteredResponses = result.openShiftResponses.filter { it.responseBody?.get("kind")?.asText() != "Secret" }
        return result.copy(openShiftResponses = filteredResponses)
    }


    fun deployHistory(ref: AuroraConfigRef): List<DeployHistory> {
        val repo = gitService.checkoutRepository(ref.name, refName = ref.refName)
        val res = gitService.getTagHistory(repo)
            .filter { it.tagName.startsWith(DEPLOY_PREFIX) }
            .map {
                val fullMessage = it.fullMessage
                it.taggerIdent.let {
                    DeployHistory(
                        Deployer(it.name, it.emailAddress),
                        it.`when`.toInstant(),
                        mapper.readTree(fullMessage)
                    )
                }
            }
        repo.close()
        return res
    }

    fun findDeployResultById(ref: AuroraConfigRef, deployId: String): DeployHistory? {
        val repo = gitService.checkoutRepository(ref.name, refName = ref.refName)
        val res: DeployHistory? = gitService.getTagHistory(repo)
            .firstOrNull { it.tagName.endsWith(deployId) }
            ?.let {
                val fullMessage = it.fullMessage
                it.taggerIdent.let {
                    DeployHistory(
                        Deployer(it.name, it.emailAddress),
                        it.`when`.toInstant(),
                        mapper.readTree(fullMessage)
                    )
                }
            }
        repo.close()
        return res
    }
}