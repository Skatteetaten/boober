package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.service.GitServices.Domain.AURORA_CONFIG
import no.skatteetaten.aurora.boober.service.GitServices.TargetDomain
import org.springframework.stereotype.Service

@Service
class DeployLogService(@TargetDomain(AURORA_CONFIG) val gitService: GitService, val mapper: ObjectMapper) {

    private val DEPLOY_PREFIX = "DEPLOY"

    private val FAILED_PREFIX = "FAILED"

    fun markRelease(auroraConfigName: String, deployResult: List<AuroraDeployResult>) {

        val repo = gitService.checkoutRepository(auroraConfigName)
        val refs = deployResult
                .filter { !it.ignored }
                .map {
                    val result = filterSensitiveInformation(it)
                    val prefix = if (it.success) DEPLOY_PREFIX else FAILED_PREFIX

                    gitService.createAnnotatedTag(repo, "$prefix/${it.tag}", mapper.writeValueAsString(result))
                }
        gitService.pushTags(repo, refs)
    }

    private fun filterSensitiveInformation(result: AuroraDeployResult): AuroraDeployResult {

        val filteredResponses = result.openShiftResponses.filter { it.responseBody?.get("kind")?.asText() != "Secret" }
        return result.copy(openShiftResponses = filteredResponses)
    }

    fun deployHistory(affiliation: String): List<DeployHistory> {
        val repo = gitService.checkoutRepository(affiliation)
        val res = gitService.getTagHistory(repo)
                .filter { it.tagName.startsWith(DEPLOY_PREFIX) }
                .map {
                    val fullMessage = it.fullMessage
                    it.taggerIdent.let { DeployHistory(Deployer(it.name, it.emailAddress), it.`when`.toInstant(), mapper.readTree(fullMessage)) }
                }
        repo.close()
        return res
    }

    fun findDeployResultById(auroraConfigId: String, deployId: String): DeployHistory? {
        val repo = gitService.checkoutRepository(auroraConfigId)
        val res: DeployHistory? = gitService.getTagHistory(repo)
                .firstOrNull { it.tagName.endsWith(deployId) }
                ?.let {
                    val fullMessage = it.fullMessage
                    it.taggerIdent.let { DeployHistory(Deployer(it.name, it.emailAddress), it.`when`.toInstant(), mapper.readTree(fullMessage)) }
                }
        repo.close()
        return res
    }
}