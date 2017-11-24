package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler

data class AuroraConfigFile(val name: String, val contents: JsonNode, val override: Boolean = false, val version: String? = null) {
    val configName
        get() = if (override) "$name.override" else name
}


