package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import org.springframework.stereotype.Service

data class SetupResult(
        val openShiftResponses: List<OpenShiftResponse>
)

@Service
class SetupService(
        val auroraConfigParserService: AuroraConfigParserService,
        val openShiftService: OpenShiftService,
        val openShiftClient: OpenShiftClient) {

    fun executeSetup(token: String, auroraConfig: AuroraConfig, environmentName: String, applicationName: String): SetupResult {

        val auroraDc: AuroraDeploymentConfig = auroraConfigParserService.createAuroraDcFromAuroraConfig(auroraConfig, environmentName, applicationName)

        val openShiftObjects: List<JsonNode> = openShiftService.generateObjects(auroraDc, token)
        val openShiftResponses: List<OpenShiftResponse> = openShiftClient.saveMany(auroraDc.namespace, openShiftObjects, token)

        return SetupResult(openShiftResponses)
    }
}