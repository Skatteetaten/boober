package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class ApplicationId(
        val environmentName: String,
        val applicationName: String
)

data class ApplicationResult(
        val applicationId: ApplicationId,
        val auroraDc: AuroraDeploymentConfig,
        val openShiftResponses: List<OpenShiftResponse>
) {
    val containsError: Boolean
        get() = openShiftResponses.any { it.status >= 400 }
}

@Service
class SetupService(val openShiftService: OpenShiftService, val openShiftClient: OpenShiftClient) {

    val logger: Logger = LoggerFactory.getLogger(SetupService::class.java)

    fun executeSetup(token: String, auroraDc: AuroraDeploymentConfig): List<ApplicationResult> {

        logger.info("Creating OpenShift objects for application ${auroraDc.name} in namespace ${auroraDc.namespace}")
        val openShiftObjects: List<JsonNode> = openShiftService.generateObjects(auroraDc, token)
        val openShiftResponses: List<OpenShiftResponse> = openShiftClient.applyMany(auroraDc.namespace, openShiftObjects, token)

        /*
        openShiftClient.updateRoleBinding(auroraDc.namespace, "admin", token,
                                          auroraDc.users?.split(" ") ?: emptyList(),
                                          auroraDc.groups?.split(" ") ?: emptyList()).let {
            openShiftResponses.plus(it)
        }
*/
        return listOf(ApplicationResult(
                applicationId = ApplicationId(auroraDc.envName, auroraDc.name),
                auroraDc = auroraDc,
                openShiftResponses = openShiftResponses
        ))
    }
}