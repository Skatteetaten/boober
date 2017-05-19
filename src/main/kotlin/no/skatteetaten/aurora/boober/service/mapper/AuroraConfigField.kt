package no.skatteetaten.aurora.boober.service.mapper

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraConfigFile

data class AuroraConfigField(val path: String, val value: JsonNode, val source: String)typealias ConfigMap = Map<String, Map<String, String>>class AuroraConfigFields(val fields: Map<String, AuroraConfigField>) {


    fun getConfigMap(configExtractors: List<AuroraConfigFieldHandler>): ConfigMap? {

        val configMap: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

        configExtractors.forEach {
            val (_, configFile, field) = it.name.split("/", limit = 3)

            val value = extract(it.name)
            val keyValue = mutableMapOf(field to value)

            if (configMap.containsKey(configFile)) configMap[configFile]?.putAll(keyValue)
            else configMap.put(configFile, keyValue)
        }

        return if (configMap.isNotEmpty()) configMap else null
    }

    fun getParameters(parameterExtractors: List<AuroraConfigFieldHandler>): Map<String, String>? {
        return parameterExtractors.map {
            val (_, field) = it.name.split("/", limit = 2)

            val value = extract(it.name)
            field to value
        }.toMap()

    }


    fun <T> findAll(name: String, mapper: (Map<String, AuroraConfigField>) -> T): T? {

        val fields = fields.entries.filter { it.key.contains(name) }.map { it.key to it.value }.toMap()

        if (fields.isEmpty()) return null

        return mapper(fields)
    }


    fun extractOrNull(name: String): String? {
        return if (fields.containsKey(name)) extract(name)
        else null
    }

    fun <T> extractOrNull(name: String, mapper: (JsonNode) -> T): T? {
        return if (fields.containsKey(name)) extract(name, mapper)
        else null
    }

    inline fun <reified T> extractOrDefault(name: String, default: T): T {
        return if (fields.containsKey(name)) jacksonObjectMapper().convertValue(fields[name]!!.value, T::class.java)
        else default
    }

    fun extract(name: String): String {
        return extract<String>(name, JsonNode::textValue)
    }

    fun <T> extract(name: String, mapper: (JsonNode) -> T): T {

        if (!fields.containsKey(name)) throw IllegalArgumentException("$name is not set")

        return mapper(fields[name]!!.value)
    }

    companion object {

        fun create(handlers: List<AuroraConfigFieldHandler>, files: List<AuroraConfigFile>): AuroraConfigFields {
            val fields = handlers.mapNotNull { (name, path, _, defaultValue) ->

                val matches = files.reversed().mapNotNull {
                    logger.debug("Sjekker om $path finnes i fil ${it.contents}")
                    val value = it.contents.at(path)

                    if (!value.isMissingNode) {
                        logger.debug("Match $value i fil ${it.configName}")
                        name to AuroraConfigField(path, value, it.configName)
                    } else null
                }

                when {
                    (matches.isEmpty() && defaultValue != null) -> {
                        logger.debug("Default match $defaultValue")
                        name to AuroraConfigField(path, TextNode(defaultValue), "default")
                    }
                    matches.isNotEmpty() -> matches.first()
                    else -> null
                }
            }.toMap()

            return AuroraConfigFields(fields)
        }
    }
}