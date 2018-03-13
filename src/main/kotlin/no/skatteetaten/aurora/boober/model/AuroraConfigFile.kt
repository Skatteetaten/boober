package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.mapper.AuroraConfigException
import no.skatteetaten.aurora.boober.model.ErrorType.INVALID
import no.skatteetaten.aurora.boober.utils.jacksonYamlObjectMapper
import org.springframework.util.DigestUtils

data class AuroraConfigFile(val name: String, val contents: String, val override: Boolean = false) {
    val configName
        get() = if (override) "$name.override" else name

    val version
        get() = DigestUtils.md5DigestAsHex(jacksonObjectMapper().writeValueAsString(contents).toByteArray())

    val asJsonNode: JsonNode by lazy {
        try {
            jacksonYamlObjectMapper().readValue(contents, JsonNode::class.java)
        } catch (e: Exception) {
            val message = "AuroraConfigFile=$name is not valid errroMessage=${e.message}"
            throw AuroraConfigException(message, listOf(ConfigFieldErrorDetail(INVALID, message)))
        }
    }
}


