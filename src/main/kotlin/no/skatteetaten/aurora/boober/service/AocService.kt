package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AocConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import org.springframework.stereotype.Service

/**
 * This service should probably not be called something with AOC in the name, since there will potentially be more
 * clients than AOC to these APIs. But the functionality of this class derives from the functionality found in AOC, so
 * AocService is a working title.
 */

data class AocResult(
        val openShiftResponses: List<OpenShiftResponse>
)

@Service
class AocService(
        val aocConfigParserService: AocConfigParserService,
        val openShiftService: OpenShiftService,
        val openShiftClient: OpenShiftClient) {

    fun executeSetup(token: String, aocConfig: AocConfig, environmentName: String, applicationName: String): AocResult {

        val auroraDc: AuroraDeploymentConfig = aocConfigParserService.createConfigFromAocConfigFiles(aocConfig, environmentName, applicationName)

        val openShiftObjects: List<JsonNode> = openShiftService.generateObjects(auroraDc, token)
        val openShiftResponses: List<OpenShiftResponse> = openShiftClient.saveMany(auroraDc.namespace, openShiftObjects, token)

        return AocResult(openShiftResponses)
    }
}