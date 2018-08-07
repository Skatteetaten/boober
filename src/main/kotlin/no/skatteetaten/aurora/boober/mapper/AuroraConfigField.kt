package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.mapper.v1.convertValueToString
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.utils.atNullable
import org.apache.commons.text.StringSubstitutor
import org.slf4j.Logger
import org.slf4j.LoggerFactory

data class AuroraConfigField(
    val fields: List<AuroraConfigFieldSource>,
    val replacer: StringSubstitutor
) {
    val source: String
        get() = fields.last().source

    val isDefault: Boolean
        get() = fields.last().defaultSource

    val valueNode: JsonNode
        get() = fields.last().value

    inline fun <reified T> getNullableValue(): T? = this.value() as T?

    inline fun <reified T> value(): T {

        val it = fields.last()!!
        val result = jacksonObjectMapper().convertValue(it.value, T::class.java)
        if (result is String) {
            return replacer.replace(result as String) as T
        }
        return result
    }

    /**
     * Extracts a config field declared either as a delimited string (ie. "value1, value2") or as a JSON array
     * (ie. ["value1", "value2"]) as a String list.
     */
    fun extractDelimitedStringOrArrayAsSet(delimiter: String = ","): Set<String> {
        val valueNode = fields.last()!!.value
        return when {
            valueNode.isTextual -> valueNode.textValue().split(delimiter).toList()
            valueNode.isArray -> (value() as List<Any?>).map { it?.toString() } // Convert any non-string values in the array to string
            else -> emptyList()
        }.filter { !it.isNullOrBlank() }
            .mapNotNull { it?.trim() }
            .toSet()
    }

    fun isSimplifiedConfig(): Boolean {
        return fields.last()!!.value.isBoolean
    }
}

data class AuroraConfigFieldSource(val source: String, val value: JsonNode, val defaultSource: Boolean = false)

class AuroraConfigFields(val fields: Map<String, AuroraConfigField>) {

    fun getConfigEnv(configExtractors: List<AuroraConfigFieldHandler>): Map<String, String> {
        val env = configExtractors.filter { it.name.count { it == '/' } == 1 }.map {
            val (_, field) = it.name.split("/", limit = 2)
            val value: Any = extract(it.name)
            val escapedValue: String = convertValueToString(value)
            field to escapedValue
        }

        return env.toMap()
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

    fun getKeyMappings(keyMappingsExtractor: AuroraConfigFieldHandler?): Map<String, String>? =
        keyMappingsExtractor?.let { extractIfExistsOrNull(it.name) }

    fun disabledAndNoSubKeys(name: String): Boolean {

        val simplified = isSimplifiedConfig(name)

        return simplified && !extract<Boolean>(name)
    }

    fun isSimplifiedConfig(name: String): Boolean {
        return fields[name]!!.isSimplifiedConfig()
    }

    inline fun <reified T> extract(name: String): T = fields[name]!!.value()

    /**
     * Extracts a config field declared either as a delimited string (ie. "value1, value2") or as a JSON array
     * (ie. ["value1", "value2"]) as a String list.
     */
    fun extractDelimitedStringOrArrayAsSet(name: String, delimiter: String = ","): Set<String> {
        return fields[name]?.extractDelimitedStringOrArrayAsSet(delimiter) ?: emptySet()
    }

    inline fun <reified T> extractOrNull(name: String): T? = fields[name]?.getNullableValue()

    inline fun <reified T> extractIfExistsOrNull(name: String): T? = fields[name]?.getNullableValue()

    companion object {

        val logger: Logger = LoggerFactory.getLogger(AuroraConfigFields::class.java)

        fun create(
            handlers: Set<AuroraConfigFieldHandler>,
            files: List<AuroraConfigFile>,
            applicationId: ApplicationId,
            configVersion: String,
            placeholders: Map<String, String> = emptyMap()
        ): AuroraConfigFields {

            val mapper = jacksonObjectMapper()

            val defaultfields: List<Pair<String, AuroraConfigFieldSource>> =
                handlers.filter { it.defaultValue != null }.map {
                    it.name to AuroraConfigFieldSource(it.defaultSource, mapper.convertValue(it.defaultValue!!), true)
                }

            val staticFields: List<Pair<String, AuroraConfigFieldSource>> =
                listOf(
                    "applicationId" to AuroraConfigFieldSource(
                        "static",
                        mapper.convertValue(applicationId.toString()),
                        true
                    )
                    , "configVersion" to AuroraConfigFieldSource("static", mapper.convertValue(configVersion), true)
                )

            val replacer = StringSubstitutor(placeholders, "@", "@")

            val fields: List<Pair<String, AuroraConfigFieldSource>> = files.flatMap { file ->
                handlers.mapNotNull { handler ->
                    file.asJsonNode.atNullable(handler.path)?.let {
                        handler.name to AuroraConfigFieldSource(file.configName, it)
                    }
                }
            }

            val allFields: List<Pair<String, AuroraConfigFieldSource>> = staticFields + defaultfields + fields

            val groupedFields: Map<String, AuroraConfigField> = allFields
                .groupBy({ it.first }) { it.second }
                .mapValues { AuroraConfigField(it.value, replacer) }
            return AuroraConfigFields(groupedFields)
        }
    }
}
