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

    fun createConfigFromAocConfigFiles(environmentName: String, applicationName: String, aocConfigFiles: Map<String, JsonNode>): Config {

        val aocConfig = AocConfig(aocConfigFiles)
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


class AocConfig(val aocConfigFiles: Map<String, JsonNode>) {

    fun getMergedFileForApplication(environmentName: String, applicationName: String) : JsonNode {
        val filesForApplication = getFilesForApplication(environmentName, applicationName)
        val mergedJson = mergeAocConfigFiles(filesForApplication)
        if (!mergedJson.has("envName")) {
            (mergedJson as ObjectNode).put("envName", "-$environmentName")
        }
        return mergedJson
    }

    fun getFilesForApplication(environmentName: String, applicationName: String): List<JsonNode> {

        val requiredFilesForApplication = setOf(
                "about.json",
                "$applicationName.json",
                "$environmentName/about.json",
                "$environmentName/$applicationName.json")

        val filesForApplication: List<JsonNode> = requiredFilesForApplication.mapNotNull { aocConfigFiles.get(it) }
        if (filesForApplication.size != requiredFilesForApplication.size) {
            val missingFiles = requiredFilesForApplication.filter { it !in aocConfigFiles.keys }
            throw AocException("Unable to execute setup command. Required files missing => $missingFiles")
        }
        return filesForApplication
    }

    private fun mergeAocConfigFiles(filesForApplication: List<JsonNode>): JsonNode {

        return filesForApplication.reduce(::createMergeCopy)
    }
}