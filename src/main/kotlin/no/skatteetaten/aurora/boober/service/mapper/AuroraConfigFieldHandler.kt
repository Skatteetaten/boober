package no.skatteetaten.aurora.boober.service.mapper

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val logger: Logger = LoggerFactory.getLogger(AuroraConfigFieldHandler::class.java)


data class AuroraConfigFieldHandler(val name: String,
                                    val path: String = "/$name",
                                    val validator: (JsonNode?) -> Exception? = { _ -> null },
                                    val defaultValue: String? = null)


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

