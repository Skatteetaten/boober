package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.cluster
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.utils.Instants.now
import no.skatteetaten.aurora.boober.utils.openshiftKind

private val logger = KotlinLogging.logger {}

@Service
class DeployLogService(
    val bitbucketService: BitbucketService,
    val mapper: ObjectMapper,
    @Value("\${integrations.deployLog.git.project}") val project: String,
    @Value("\${integrations.deployLog.git.repo}") val repo: String,
    val userDetailsProvider: UserDetailsProvider
) {

    private val DEPLOY_PREFIX = "DEPLOY"

    private val FAILED_PREFIX = "FAILED"

    fun markRelease(
        deployResult: List<AuroraDeployResult>
    ): List<AuroraDeployResult> {

        val deployer = userDetailsProvider.getAuthenticatedUser().let {
            Deployer(it.fullName ?: it.username, "${it.username}@skatteetaten.no")
        }

        return deployResult
            .map { auroraDeployResult ->
                val filteredOpenshiftResponses = filterOpenshiftResponses(auroraDeployResult.openShiftResponses)

                val deployHistory = DeployHistoryEntry(
                    command = auroraDeployResult.command,
                    deployer = deployer,
                    time = now,
                    deploymentSpec = renderSpecAsJson(auroraDeployResult.auroraDeploymentSpecInternal, true),
                    deployId = auroraDeployResult.deployId,
                    success = auroraDeployResult.success,
                    reason = auroraDeployResult.reason ?: "",
                    result = DeployHistoryEntryResult(filteredOpenshiftResponses, auroraDeployResult.tagResponse),
                    projectExist = auroraDeployResult.projectExist
                )

                try {
                    val storeResult =
                        storeDeployHistory(deployHistory, auroraDeployResult.auroraDeploymentSpecInternal.cluster)
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
        val content = jacksonObjectMapper().findAndRegisterModules().writeValueAsString(deployHistoryEntry)
        return bitbucketService.uploadFile(project, repo, fileName, message, content)
    }

    private fun filterOpenshiftResponses(responses: List<OpenShiftResponse>): List<OpenShiftResponse> {
        return responses.filter { it.responseBody?.openshiftKind != "secret" }
            .filter { it.command.payload.openshiftKind != "secret" }
            .filter { it.command.previous?.get("kind")?.asText()?.lowercase() != "secret" }
    }

    fun deployHistory(ref: AuroraConfigRef): List<DeployHistoryEntry> {
        val files = bitbucketService.getFiles(project, repo, ref.name)

        return files.mapNotNull {
            bitbucketService.getFile(project, repo, "${ref.name}/$it")
                ?.toDeployHistoryEntry()
        }
    }

    fun findDeployResultById(ref: AuroraConfigRef, deployId: String): DeployHistoryEntry? {
        return try {
            bitbucketService.getFile(project, repo, "${ref.name}/$deployId.json")
                ?.toDeployHistoryEntry()
        } catch (e: HttpClientErrorException) {
            logger.warn("Client exception when finding deploy result, status=${e.statusCode} message=\"${e.message}\"")
            if (e.statusCode == HttpStatus.NOT_FOUND) {
                throw DeployLogServiceException("DeployId $deployId was not found for affiliation ${ref.name}")
            } else {
                throw e
            }
        }
    }

    private fun String.toDeployHistoryEntry(): DeployHistoryEntry {
        val entry = mapper.readValue<DeployHistoryEntry>(this)
        val filteredResponses = filterOpenshiftResponses(entry.result.openshift)
        val result = entry.result.copy(openshift = filteredResponses)
        return entry.copy(result = result)
    }
}
