package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.Config
import no.skatteetaten.aurora.boober.model.TemplateProcessingConfig
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.utils.createMergeCopy
import org.springframework.stereotype.Service


@Service
class ConfigService(val mapper: ObjectMapper) {

    fun createConfigFormAocConfigFiles(environmentName: String, applicationName: String, aocConfigFiles: Map<String, JsonNode>): Config {

        val requiredFilesForApplication = setOf(
                "about.json",
                "$environmentName/about.json",
                "$applicationName.json",
                "$environmentName/$applicationName.json")

        // Not sure this will maintain the required order of the setup files. Should probably map over required files instead.
        val filesForApplication = aocConfigFiles.filter { it.key in requiredFilesForApplication }.values.toList()
        if (filesForApplication.size != requiredFilesForApplication.size) {
            val missingFiles = requiredFilesForApplication.filter { it !in aocConfigFiles.keys }
            throw AocException("Unable to execute setup command. Required files missing => $missingFiles")
        }

        val mergedJson = mergeAocConfigFiles(filesForApplication)
        if (!mergedJson.has("envName")) {
            (mergedJson as ObjectNode).put("envName", "-$environmentName")
        }

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

    private fun mergeAocConfigFiles(filesForApplication: List<JsonNode>): JsonNode {

        val mergedJson = filesForApplication.reduce(::createMergeCopy)

        return mergedJson
    }
}