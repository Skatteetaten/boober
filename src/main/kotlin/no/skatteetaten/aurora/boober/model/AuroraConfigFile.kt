package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.utils.jacksonYamlObjectMapper
import org.springframework.util.DigestUtils

data class AuroraConfigFile(val name: String, val contents: String, val override: Boolean = false) {
    val configName
        get() = if (override) "$name.override" else name

    val version
        get() = DigestUtils.md5DigestAsHex(jacksonObjectMapper().writeValueAsString(contents).toByteArray())

    val asJsonNode: JsonNode by lazy {
        jacksonYamlObjectMapper().readValue(contents, JsonNode::class.java)
    }
}


