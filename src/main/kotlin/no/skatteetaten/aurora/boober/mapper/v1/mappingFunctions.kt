package no.skatteetaten.aurora.boober.mapper.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import org.apache.commons.lang.StringEscapeUtils

fun List<AuroraConfigFile>.findSubKeys(name: String): Set<String> {
    return this.flatMap {
        if (it.asJsonNode.has(name)) {
            it.asJsonNode[name].fieldNames().asSequence().toList()
        } else {
            emptyList()
        }
    }.toSet()
}

fun List<AuroraConfigFile>.findConfigFieldHandlers(): List<AuroraConfigFieldHandler> {

    val name = "config"
    val keysStartingWithConfig = this.findSubKeys(name)

    val configKeys: Map<String, Set<String>> = keysStartingWithConfig.map { configFileName ->
        //find all unique keys in a configFile
        val keys = this.flatMap { ac ->
            ac.asJsonNode.at("/$name/$configFileName")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
        }.toSet()

        configFileName to keys
    }.toMap()

    return configKeys.flatMap { configFile ->
        val value = configFile.value
        if (value.isEmpty()) {
            listOf(AuroraConfigFieldHandler("$name/${configFile.key}"))
        } else {
            value.map { field ->
                AuroraConfigFieldHandler("$name/${configFile.key}/$field")
            }
        }
    }
}

fun convertValueToString(value: Any): String {
    return when (value) {
        is String -> StringEscapeUtils.escapeJavaScript(value)
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> StringEscapeUtils.escapeJavaScript(jacksonObjectMapper().writeValueAsString(value))
    }
}
