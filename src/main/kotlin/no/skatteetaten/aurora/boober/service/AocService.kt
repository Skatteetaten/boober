package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.Config
import no.skatteetaten.aurora.boober.model.TemplateProcessingConfig
import no.skatteetaten.aurora.boober.model.TemplateType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
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
        val mapper: ObjectMapper,
        val validationService: ValidationService,
        val openshiftService: OpenshiftService,
        val openShiftClient: OpenShiftClient) {

    fun executeSetup(token: String, aocConfig: AocConfig, environmentName: String, applicationName: String): AocResult {

        val config: Config = createConfigFromAocConfigFiles(aocConfig, environmentName, applicationName)

        return when (config) {
            is AuroraDeploymentConfig -> handleAuroraDeploymentConfig(config, token)
            is TemplateProcessingConfig -> handleTemplateProcessingConfig(config, token)
            else -> AocResult(listOf())
        }
    }

    private fun handleAuroraDeploymentConfig(config: AuroraDeploymentConfig, token: String): AocResult {

        val openShiftObjects: List<JsonNode> = openshiftService.generateObjects(config, token)
        val openShiftResponses: List<OpenShiftResponse> = openShiftClient.saveMany(config.namespace, openShiftObjects, token)
        return AocResult(openShiftResponses)
    }

    private fun handleTemplateProcessingConfig(config: TemplateProcessingConfig, token: String): AocResult {
        TODO("not implemented")
    }

    fun createConfigFromAocConfigFiles(aocConfig: AocConfig, environmentName: String, applicationName: String): Config {

        val mergedJson = aocConfig.getMergedFileForApplication(environmentName, applicationName)

        val type = TemplateType.valueOf(mergedJson.get("type").asText())
        val clazz: Class<*> = when (type) {
            TemplateType.process -> TemplateProcessingConfig::class.java
            else -> AuroraDeploymentConfig::class.java
        }

        val config: Config?
        try {
            config = mapper.reader().forType(clazz).readValue<Config>(mergedJson.toString())
        } catch (ex: JsonMappingException) {
            val missingProp = ex.path.map { it.fieldName }.reduce { acc, fieldName -> acc + ".$fieldName" }
            throw AocException("$missingProp is required", ex)
        }

        validationService.assertIsValid(config)

        return config
    }
}