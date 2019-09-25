package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraConfigFile

fun List<AuroraConfigFile>.findSubKeysExpanded(name: String): Set<String> {
    return this.flatMap { ac ->
        ac.asJsonNode.at("/$name")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
    }.map {
        "$name/$it"
    }.toSet()
}

fun List<AuroraConfigFile>.findSubKeys(name: String): Set<String> {
    return this.flatMap { ac ->
        ac.asJsonNode.at("/$name")?.fieldNames()?.asSequence()?.toList() ?: emptyList()
    }.toSet()
}
inline fun <reified T> List<AuroraConfigFile>.associateSubKeys(
        name: String,
        spec: AuroraDeploymentContext
): Map<String, T> {
    return this.findSubKeys(name).associateWith {
        spec.get<T>("$name/$it")
    }
}


fun List<AuroraConfigFile>.findSubHandlers(
    key: String,
    validatorFn: (k: String) -> Validator = { defaultValidator }
): List<AuroraConfigFieldHandler> {

    return findSubKeys(key).map { subKey ->
        AuroraConfigFieldHandler("$key/$subKey", validator = validatorFn(subKey))
    }
}

fun List<AuroraConfigFile>.findConfigFieldHandlers(): List<AuroraConfigFieldHandler> {

    val name = "config"
    val keysStartingWithConfig = this.findSubKeys(name)

    val configKeys: Map<String, Set<String>> = keysStartingWithConfig.associateWith { findSubKeys("$name/$it") }

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
        is String -> value
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> jacksonObjectMapper().writeValueAsString(value)
    }
}
