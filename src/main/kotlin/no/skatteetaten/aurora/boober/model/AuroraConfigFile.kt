package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode

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