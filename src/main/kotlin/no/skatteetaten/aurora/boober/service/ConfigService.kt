package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.Config
import no.skatteetaten.aurora.boober.model.TemplateProcessingConfig
import no.skatteetaten.aurora.boober.model.TemplateType
import org.springframework.stereotype.Service


@Service
class ConfigService(val mapper: ObjectMapper) {

    fun createConfigFromAocConfigFiles(aocConfig: AocConfig, environmentName: String, applicationName: String): Config {

        val mergedJson = aocConfig.getMergedFileForApplication(environmentName, applicationName)

        val type = TemplateType.valueOf(mergedJson.get("type").asText())
        val clazz: Class<*> = when (type) {
            TemplateType.process -> TemplateProcessingConfig::class.java
            else -> AuroraDeploymentConfig::class.java
        }

        try {
            return mapper.reader().forType(clazz).readValue(mergedJson.toString())
        } catch (ex: JsonMappingException) {
            val missingProp = ex.path.map { it.fieldName }.reduce { acc, fieldName -> acc + ".$fieldName" }
            throw AocException("$missingProp is required")
        }
    }
}