package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraConfigFile


class AuroraConfigFieldHandler(val name: String,
                               val path: String = "/$name",
                               val validator: (JsonNode?) -> Exception? = { _ -> null },
                               val defaultValue: String? = null)

fun List<AuroraConfigFile>.findConfig(): List<AuroraConfigFieldHandler> {

    val name = "config"
    val configFiles = findSubKeys(name)

    val configKeys: Map<String, Set<String>> = configFiles.map { configFileName ->
        //find all unique keys in a configFile
        val keys = this.flatMap { ac ->
            ac.contents.at("/$name/$configFileName")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
        }.toSet()

        configFileName to keys
    }.toMap()

    val result = configKeys.flatMap { configFile ->
        configFile.value.map { field ->
            AuroraConfigFieldHandler("$name/${configFile.key}/$field")
        }
    }
    return result
}

fun List<AuroraConfigFile>.findParameters(): List<AuroraConfigFieldHandler> {

    val parameterKeys = findSubKeys("parameters")

    val result = parameterKeys.map { parameter ->
        AuroraConfigFieldHandler("parameters/$parameter")
    }
    return result
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

