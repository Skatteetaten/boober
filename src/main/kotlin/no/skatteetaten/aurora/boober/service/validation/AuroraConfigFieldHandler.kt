package no.skatteetaten.aurora.boober.service.validation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val logger: Logger = LoggerFactory.getLogger(AuroraConfigFieldHandler::class.java)


data class AuroraConfigFieldHandler(val name: String,
                                    val path: String = "/$name",
                                    val validator: (JsonNode?) -> Exception? = { _ -> null },
                                    val defaultValue: String? = null)


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

fun List<AuroraConfigFile>.findConfigExtractors(): List<AuroraConfigFieldHandler> {

    val configFiles = this.flatMap {
        if (it.contents.has("config")) {
            it.contents["config"].fieldNames().asSequence().toList()
        } else {
            emptyList()
        }
    }.toSet()

    val configKeys: Map<String, Set<String>> = configFiles.map { configFileName ->
        //find all unique keys in a configFile
        val keys = this.flatMap { ac ->
            ac.contents.at("/config/$configFileName")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
        }.toSet()

        configFileName to keys
    }.toMap()

    return configKeys.flatMap { configFile ->
        configFile.value.map { field ->
            AuroraConfigFieldHandler("config/${configFile.key}/$field")
        }
    }
}

fun Map<String, AuroraConfigField>.extractOrNull(name: String): String? {
    return if (this.containsKey(name)) this.extract(name)
    else null
}

fun <T> Map<String, AuroraConfigField>.extractOrNull(name: String, mapper: (JsonNode) -> T): T? {
    return if (this.containsKey(name)) this.extract(name, mapper)
    else null
}

fun <T> Map<String, AuroraConfigField>.extractOrDefault(name: String, default: T): T {
    return if (this.containsKey(name)) this.extract(name) as T
    else default
}

fun Map<String, AuroraConfigField>.extract(name: String): String {
    return this.extract<String>(name, JsonNode::textValue)
}

fun <T> Map<String, AuroraConfigField>.extract(name: String, mapper: (JsonNode) -> T): T {

    if (!this.containsKey(name)) throw IllegalArgumentException("$name is not set")

    return mapper(this[name]!!.value)
}
