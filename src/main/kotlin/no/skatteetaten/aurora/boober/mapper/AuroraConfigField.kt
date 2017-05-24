package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

data class AuroraConfigField(val path: String, val value: JsonNode, val source: String)

typealias ConfigMap = Map<String, Map<String, String>>

class AuroraConfigFields(val fields: Map<String, AuroraConfigField>) {

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

    fun getRouteAnnotations(extractors: List<AuroraConfigFieldHandler>): Map<String, String>? {
        return extractors.map {
            val (_, _, field) = it.name.split("/", limit = 3)

            val value = extract(it.name)
            field to value
        }.toMap()

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

        val logger: Logger = LoggerFactory.getLogger(AuroraConfigFields::class.java)
        fun create(handlers: List<AuroraConfigFieldHandler>, files: List<AuroraConfigFile>): AuroraConfigFields {
            val fields = handlers.mapNotNull { handler ->

                val matches = files.reversed().mapNotNull {
                    logger.debug("Sjekker om ${handler.path} finnes i fil ${it.contents}")
                    val value = it.contents.at(handler.path)

                    if (!value.isMissingNode) {
                        logger.debug("Match $value i fil ${it.configName}")
                        handler.name to AuroraConfigField(handler.path, value, it.configName)
                    } else null
                }

                when {
                    (matches.isEmpty() && handler.defaultValue != null) -> {
                        logger.debug("Default match ${handler.defaultValue}")
                        handler.name to AuroraConfigField(handler.path, TextNode(handler.defaultValue), "default")
                    }
                    matches.isNotEmpty() -> matches.first()
                    else -> null
                }
            }.toMap()

            return AuroraConfigFields(fields)
        }
    }
}