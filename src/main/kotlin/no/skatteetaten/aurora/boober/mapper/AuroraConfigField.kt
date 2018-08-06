package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.mapper.v1.convertValueToString
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.utils.atNullable
import org.apache.commons.text.StringSubstitutor
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AuroraConfigField(
    val handler: AuroraConfigFieldHandler,
    val replacer: StringSubstitutor,
    val source: AuroraConfigFile? = null
) {
    val valueNode: JsonNode
        get() = source?.asJsonNode?.at(handler.path) ?: MissingNode.getInstance()
    val valueNodeOrDefault: JsonNode?
        get() =
            valueNode.let {
                if (it.isMissingNode) {
                    jacksonObjectMapper().convertValue(handler.defaultValue, JsonNode::class.java)
                } else {
                    it
                }
            }

    inline fun <reified T> getNullableValue(): T? = this.value() as T?

    inline fun <reified T> value(): T {

        val result = if (this.source == null) {
            this.handler.defaultValue as T
        } else {
            jacksonObjectMapper().convertValue(this.valueNode, T::class.java)
        }
        if (result is String) {
            return replacer.replace(result as String) as T
        }
        return result
    }
}

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

    inline fun <reified T> extract(name: String): T = fields[name]!!.value()

    /**
     * Extracts a config field declared either as a delimited string (ie. "value1, value2") or as a JSON array
     * (ie. ["value1", "value2"]) as a String list.
     */
    fun extractDelimitedStringOrArrayAsSet(name: String, delimiter: String = ","): Set<String> {
        val field = fields[name]!!
        val valueNode = field.valueNode
        return when {
            valueNode.isTextual -> valueNode.textValue().split(delimiter).toList()
            valueNode.isArray -> (field.value() as List<Any?>).map { it?.toString() } // Convert any non-string values in the array to string
            else -> emptyList()
        }.filter { !it.isNullOrBlank() }
            .mapNotNull { it?.trim() }
            .toSet()
    }

    inline fun <reified T> extractOrNull(name: String): T? = fields[name]!!.getNullableValue()

    inline fun <reified T> extractIfExistsOrNull(name: String): T? = fields[name]?.getNullableValue()

    companion object {

        val logger: Logger = LoggerFactory.getLogger(AuroraConfigFields::class.java)

        fun create(
            handlers: Set<AuroraConfigFieldHandler>,
            files: List<AuroraConfigFile>,
            placeholders: Map<String, String> = emptyMap()
        ): AuroraConfigFields {

            val replacer = StringSubstitutor(placeholders, "@", "@")

            val fields: Map<String, AuroraConfigField> = handlers.map { handler ->
                val matches = files.reversed().mapNotNull {
                    logger.trace("Check if  ${handler.path} exist in file  ${it.contents}")
                    val value = it.asJsonNode.at(handler.path)

                    if (!value.isMissingNode) {
                        logger.trace("Match $value i fil ${it.configName}")
                        AuroraConfigField(handler, replacer, it)
                    } else null
                }

                matches.firstOrNull()?.let {
                    it
                } ?: AuroraConfigField(handler, replacer)
            }.associate { it.handler.name to it }

            return AuroraConfigFields(fields)
        }

        fun create2(
            handlers: Set<AuroraConfigFieldHandler>,
            files: List<AuroraConfigFile>,
            placeholders: Map<String, String> = emptyMap()
        ): AuroraConfigFields2 {

            val mapper = jacksonObjectMapper()

            val defaultfields: List<Pair<String, AuroraConfigField2>> =
                handlers.filter { it.defaultValue != null }.map {
                    it.path to AuroraConfigField2(it.defaultSource, mapper.convertValue(it.defaultValue!!))
                }

            val replacer = StringSubstitutor(placeholders, "@", "@")

            val fields: List<Pair<String, AuroraConfigField2>> = files.flatMap { file ->
                handlers.mapNotNull { handler ->
                    file.asJsonNode.atNullable(handler.path)?.let {
                        handler.path to AuroraConfigField2(file.configName, it)
                    }
                }
            }

            val allFields: List<Pair<String, AuroraConfigField2>> = defaultfields + fields

            val groupedFields: Map<String, List<AuroraConfigField2>> = allFields
                .groupBy({ it.first }) { it.second }
            return AuroraConfigFields2(replacer, groupedFields)
        }
    }
}

data class AuroraConfigFields2(
    val replacer: StringSubstitutor,
    val fields: Map<String, List<AuroraConfigField2>>
) {

    inline fun <reified T> extractIfExistsOrNull(path: String): T? {

        return fields[path]?.lastOrNull()?.let {
            getValue<T>(it)
        }
    }

    inline fun <reified T> extractOrNull(path: String): T? {

        return fields[path]?.lastOrNull()?.let {
            getValue<T>(it)
        }
    }

    inline fun <reified T> getValue(it: AuroraConfigField2): T {
        val result = jacksonObjectMapper().convertValue(it.value, T::class.java)
        if (result is String) {
            return replacer.replace(result as String) as T
        }
        return result
    }

    inline fun <reified T> extract(path: String): T {
        return extractOrNull<T>(path) ?: throw IllegalArgumentException("Path=$path is not set")
    }

    /**
     * Extracts a config field declared either as a delimited string (ie. "value1, value2") or as a JSON array
     * (ie. ["value1", "value2"]) as a String list.
     */
    fun extractDelimitedStringOrArrayAsSet(path: String, delimiter: String = ","): Set<String> {
        val valueNode = fields[path]!!.last().value
        return when {
            valueNode.isTextual -> valueNode.textValue().split(delimiter).toList()
            valueNode.isArray -> (extract(path) as List<Any?>).map { it?.toString() } // Convert any non-string values in the array to string
            else -> emptyList()
        }.filter { !it.isNullOrBlank() }
            .mapNotNull { it?.trim() }
            .toSet()
    }

    fun getConfigEnv(configExtractors: List<AuroraConfigFieldHandler>): Map<String, String> {
        val env = configExtractors.filter { it.name.count { it == '/' } == 1 }.map {
            val (_, field) = it.name.split("/", limit = 2)
            val value: Any = extract(it.path)
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

                val value: String = extract(it.path)
                field to value
            }.toMap()
    }

    fun getDatabases(extractors: List<AuroraConfigFieldHandler>): List<Database> {

        return extractors.map {
            val (_, field) = it.name.split("/", limit = 2)

            val value: String = extract(it.path)
            Database(field, if (value == "auto" || value.isBlank()) null else value)
        }
    }

    fun getParameters(parameterExtractors: List<AuroraConfigFieldHandler>): Map<String, String>? {
        return parameterExtractors.map {
            val (_, field) = it.name.split("/", limit = 2)

            val value: String = extract(it.path)
            field to value
        }.toMap()
    }

    fun getKeyMappings(keyMappingsExtractor: AuroraConfigFieldHandler?): Map<String, String>? =
        keyMappingsExtractor?.let { extractIfExistsOrNull(it.path) }

    fun disabledAndNoSubKeys(name: String): Boolean {

        val simplified = isSimplifiedConfig(name)

        return simplified && !extract<Boolean>(name)
    }

    fun isSimplifiedConfig(name: String): Boolean {
        val field = fields[name]!!.last()

        if (field.value.isBoolean) {
            return true
        }

        return false
    }
}

data class AuroraConfigField2(val source: String, val value: JsonNode)
