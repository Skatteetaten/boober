package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.APP
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.APP_OVERRIDE
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.BASE
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.BASE_OVERRIDE
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.DEFAULT
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.ENV
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.ENV_OVERRIDE
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.GLOBAL
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.GLOBAL_OVERRIDE
import no.skatteetaten.aurora.boober.model.ErrorType.INVALID
import no.skatteetaten.aurora.boober.utils.jacksonYamlObjectMapper
import org.springframework.util.DigestUtils

enum class AuroraConfigFileType {
    DEFAULT,
    GLOBAL,
    GLOBAL_OVERRIDE,
    BASE,
    BASE_OVERRIDE,
    INCLUDE_ABOUT,
    ENV,
    ENV_OVERRIDE,
    APP,
    APP_OVERRIDE
}

data class AuroraConfigFileSpec(
    val name: String,
    val type: AuroraConfigFileType
)

data class AuroraConfigFile(
    val name: String,
    val contents: String,
    val override: Boolean = false,
    val isDefault: Boolean = false,
    val typeHint: AuroraConfigFileType? = null
) {
    val configName
        get() = if (override) "$name.override" else name

    val version
        get() = DigestUtils.md5DigestAsHex(jacksonObjectMapper().writeValueAsString(contents).toByteArray())

    // TODO: This cannot be inferred anymore, set it when we create the app
    val type: AuroraConfigFileType
        get() {

            val appSpecificFile = !(name.startsWith("about") || name.contains("/about"))
            val hasSubFolder = name.contains("/")

            return typeHint ?: when {
                isDefault -> DEFAULT
                !hasSubFolder && !appSpecificFile && !override -> GLOBAL
                !hasSubFolder && !appSpecificFile && override -> GLOBAL_OVERRIDE
                !hasSubFolder && appSpecificFile && !override -> BASE
                !hasSubFolder && appSpecificFile && override -> BASE_OVERRIDE
                hasSubFolder && !appSpecificFile && !override -> ENV
                hasSubFolder && !appSpecificFile && override -> ENV_OVERRIDE
                hasSubFolder && appSpecificFile && !override -> APP
                hasSubFolder && appSpecificFile && override -> APP_OVERRIDE
                else -> DEFAULT
            }
        }

    val asJsonNode: JsonNode by lazy {
        try {

            val mapper = if (name.endsWith(".json")) {
                jacksonObjectMapper()
            } else if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                jacksonYamlObjectMapper()
            } else {
                null
            }

            val fixedContent = if ((name.endsWith(".yaml") || name.endsWith(".yml")) && contents.trim() == "---") {
                "{}"
            } else contents

            mapper?.readValue(fixedContent, JsonNode::class.java) ?: TextNode(contents)
        } catch (e: Exception) {
            val message = "AuroraConfigFile=$name is not valid errorMessage=${e.message}"
            throw AuroraConfigException(
                message,
                listOf(ConfigFieldErrorDetail(INVALID, message))
            )
        }
    }
}

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
    spec: AuroraDeploymentSpec
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
