package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.model.*

class AocConfigParserService(
        val validationService: ValidationService
) {

    fun createConfigFromAocConfigFiles(aocConfig: AocConfig, environmentName: String, applicationName: String): Config {

        val mergedJson = aocConfig.getMergedFileForApplication(environmentName, applicationName)

        val schemaVersion = mergedJson.get("schemaVersion")?.asText() ?: "v1"

        if (schemaVersion != "v1") {
            TODO("Only schema v1 supported")
        }

        val config = createAuroraDeploymentConfig(mergedJson)

        validationService.assertIsValid(config)

        return config
/*
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

*/

        return config
    }

    private fun createAuroraDeploymentConfig(mergedJson: JsonNode): Config {

        fun s(field: String) = mergedJson.get(field)?.asText() ?: ""
        fun i(field: String) = mergedJson.get(field)?.asInt() ?: -1
        fun l(field: String): List<String> = mergedJson.get(field)?.toList()?.map(JsonNode::asText) ?: listOf()
        fun m(field: String): Map<String, String> {
            val objectNode = mergedJson.get(field) as ObjectNode?
            return mutableMapOf<String, String>().apply {
                objectNode?.fields()?.forEach { put(it.key, it.value.asText()) }
            }
        }

        val auroraDeploymentConfig = AuroraDeploymentConfig(
                affiliation = s("affiliation"),
                cluster = s("cluster"),
                config = m("config"),
                envName = s("envName"),
                flags = l("flags"),
                groups = s("groups"),
                name = s("name"),
                replicas = i("replicas"),
                secretFile = s("secretFile"),
                type = TemplateType.valueOf(s("type")),
                users = s("users"),
                build = ConfigBuild("", "", "", "")
        )

        return auroraDeploymentConfig
    }
}