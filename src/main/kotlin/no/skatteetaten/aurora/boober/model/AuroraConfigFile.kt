package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler

data class AuroraConfigFile(val name: String, val contents: JsonNode, val override: Boolean = false, val version: String? = null) {
    val configName
        get() = if (override) "$name.override" else name
}


fun List<AuroraConfigFile>.findSubKeys(name: String): Set<String> {
    return this.flatMap {
        if (it.contents.has(name)) {
            it.contents[name].fieldNames().asSequence().toList()
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
            ac.contents.at("/$name/$configFileName")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
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