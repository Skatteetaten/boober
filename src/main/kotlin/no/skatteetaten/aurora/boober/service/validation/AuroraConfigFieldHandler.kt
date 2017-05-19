package no.skatteetaten.aurora.boober.service.validation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val logger: Logger = LoggerFactory.getLogger(AuroraConfigFieldHandler::class.java)


data class AuroraConfigFieldHandler(val name: String,
                                    val path: String = "/$name",
                                    val validator: (JsonNode?) -> Exception? = { _ -> null },
                                    val defaultValue: String? = null)


data class AuroraConfigField(val path: String, val value: JsonNode, val source: String)

fun Map<String, AuroraConfigField>.getConfigMap(configExtractors: List<AuroraConfigFieldHandler>): Map<String, Map<String, String>>? {

    val configMap: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    configExtractors.forEach {
        val (_, configFile, field) = it.name.split("/", limit = 3)

        val value = this.extract(it.name)
        val keyValue = mutableMapOf(field to value)

        if (configMap.containsKey(configFile)) configMap[configFile]?.putAll(keyValue)
        else configMap.put(configFile, keyValue)
    }

    return if (configMap.isNotEmpty()) configMap else null
}


fun Map<String, AuroraConfigField>.getParameters(parameterExtractors: List<AuroraConfigFieldHandler>): Map<String, String>? {
    return parameterExtractors.map {
        val (_, field) = it.name.split("/", limit = 2)

        val value = this.extract(it.name)
        field to value
    }.toMap()

}

fun List<AuroraConfigFieldHandler>.extractFrom(files: List<AuroraConfigFile>): Map<String, AuroraConfigField> {

    return this.mapNotNull { (name, path, _, defaultValue) ->

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
}


fun List<AuroraConfigFile>.findExtractors(name: String): List<AuroraConfigFieldHandler> {

    val configFiles = this.flatMap {
        if (it.contents.has(name)) {
            it.contents[name].fieldNames().asSequence().toList()
        } else {
            emptyList()
        }
    }.toSet()

    val configKeys: Map<String, Set<String>> = configFiles.map { configFileName ->
        //find all unique keys in a configFile
        val keys = this.flatMap { ac ->
            ac.contents.at("/$name/$configFileName")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
        }.toSet()

        configFileName to keys
    }.toMap()

    return configKeys.flatMap { configFile ->
        configFile.value.map { field ->
            AuroraConfigFieldHandler("$name/${configFile.key}/$field")
        }
    }
}

fun <T> Map<String, AuroraConfigField>.findAll(name: String, mapper: (Map<String, AuroraConfigField>) -> T): T? {

    val fields = this.entries.filter { it.key.contains(name) }.map { it.key to it.value }.toMap()

    if (fields.isEmpty()) return null

    return mapper(fields)
}

fun Map<String, AuroraConfigField>.extractOrNull(name: String): String? {
    return if (this.containsKey(name)) this.extract(name)
    else null
}

fun <T> Map<String, AuroraConfigField>.extractOrNull(name: String, mapper: (JsonNode) -> T): T? {
    return if (this.containsKey(name)) this.extract(name, mapper)
    else null
}

inline fun <reified T> Map<String, AuroraConfigField>.extractOrDefault(name: String, default: T): T {
    return if (this.containsKey(name)) jacksonObjectMapper().convertValue(this[name]!!.value, T::class.java)
    else default
}

fun Map<String, AuroraConfigField>.extract(name: String): String {
    return this.extract<String>(name, JsonNode::textValue)
}

fun <T> Map<String, AuroraConfigField>.extract(name: String, mapper: (JsonNode) -> T): T {

    if (!this.containsKey(name)) throw IllegalArgumentException("$name is not set")

    return mapper(this[name]!!.value)
}
