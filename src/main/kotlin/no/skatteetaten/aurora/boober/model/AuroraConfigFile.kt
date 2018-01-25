package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.util.DigestUtils

data class AuroraConfigFile(val name: String, val contents: JsonNode, val override: Boolean = false) {
    val configName
        get() = if (override) "$name.override" else name

    val version
        get() = DigestUtils.md5DigestAsHex(jacksonObjectMapper().writeValueAsString(contents).toByteArray())
}


