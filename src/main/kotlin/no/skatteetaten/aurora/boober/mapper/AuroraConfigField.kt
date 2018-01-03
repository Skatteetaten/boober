package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.EncryptedFileVault
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import org.apache.commons.lang.StringEscapeUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory


data class AuroraConfigField(val handler: AuroraConfigFieldHandler, val source: AuroraConfigFile? = null) {
    val value: JsonNode
        get() = source?.contents?.at(handler.path) ?: MissingNode.getInstance()
    val valueOrDefault: JsonNode?
        get() =
            value.let {
                if (it.isMissingNode) {
                    jacksonObjectMapper().convertValue(handler.defaultValue, JsonNode::class.java)
                } else {
                    it
                }
            }
}

inline fun <reified T> AuroraConfigField.getNullableValue(): T? {

    if (this.source == null) {
        return this.handler.defaultValue as T
    }

    val value = this.value

    return jacksonObjectMapper().convertValue(value, T::class.java)
}

inline fun <reified T> AuroraConfigField.value(): T {

    if (this.source == null) {
        return this.handler.defaultValue as T
    }

    val value = this.source.contents.at(this.handler.path)

    return jacksonObjectMapper().convertValue(value, T::class.java)

}

class AuroraConfigFields(val fields: Map<String, AuroraConfigField>) {


    fun getConfigEnv(configExtractors: List<AuroraConfigFieldHandler>): Map<String, String> {
        val env = configExtractors.filter { it.name.count { it == '/' } == 1 }.map {
            val (_, field) = it.name.split("/", limit = 2)
            val value: Any = extract(it.name)
            //TODO: er det rett å escape her?
            val escapedValue: String = when (value) {
                is String -> StringEscapeUtils.escapeJavaScript(value)
                is Number -> value.toString()
                is Boolean -> value.toString()
                else  ->  StringEscapeUtils.escapeJavaScript(jacksonObjectMapper().writeValueAsString(value))
            }
            field to escapedValue
        }

        return env.filter { !it.second.isBlank() }.toMap()
    }

    fun getRouteAnnotations(prefix: String, extractors: List<AuroraConfigFieldHandler>): Map<String, String> {
        return extractors
                .filter { it.path.startsWith("/$prefix") }
                .map {
                    val (_, _, _, field) = it.name.split("/", limit = 4)

                    val value: String = extract(it.name)
                    field to value
                }.toMap()
    }

    fun getDatabases(extractors: List<AuroraConfigFieldHandler>): List<Database> {

        return extractors.map {
            val (_, field) = it.name.split("/", limit = 2)

            val value: String = extract(it.name)
            Database(field, if (value == "auto" || value.isBlank()) null else value)
        }
    }


    fun getParameters(parameterExtractors: List<AuroraConfigFieldHandler>): Map<String, String>? {
        return parameterExtractors.map {
            val (_, field) = it.name.split("/", limit = 2)

            val value: String = extract(it.name)
            field to value
        }.toMap()
    }

    fun disabledAndNoSubKeys(name: String): Boolean {

        val simplified = isSimplifiedConfig(name)

        return simplified && !extract<Boolean>(name)

    }

    fun isSimplifiedConfig(name: String): Boolean {
        val field = fields[name]!!

        if (field.source == null) {
            return field.handler.defaultValue is Boolean
        }
        val value = field.source.contents.at(field.handler.path)

        if (value.isBoolean) {
            return true
        }

        return false
    }

    inline fun <reified T> extract(name: String): T = fields[name]!!.value()

    inline fun <reified T> extractOrNull(name: String): T? = fields[name]!!.getNullableValue()


    companion object {

        val logger: Logger = LoggerFactory.getLogger(AuroraConfigFields::class.java)

        fun create(handlers: Set<AuroraConfigFieldHandler>, files: List<AuroraConfigFile>): AuroraConfigFields {
            val fields: Map<String, AuroraConfigField> = handlers.map { handler ->
                val matches = files.reversed().mapNotNull {
                    logger.trace("Check if  ${handler.path} exist in file  ${it.contents}")
                    val value = it.contents.at(handler.path)

                    if (!value.isMissingNode) {
                        logger.trace("Match $value i fil ${it.configName}")
                        AuroraConfigField(handler, it)
                    } else null
                }

                matches.firstOrNull()?.let {
                    it
                } ?: AuroraConfigField(handler)

            }.associate { it.handler.name to it }

            return AuroraConfigFields(fields)
        }

    }
}
