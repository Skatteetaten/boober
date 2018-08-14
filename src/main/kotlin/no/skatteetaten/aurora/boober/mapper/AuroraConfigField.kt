package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.mapper.v1.convertValueToString
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.Database
import no.skatteetaten.aurora.boober.utils.atNullable
import no.skatteetaten.aurora.boober.utils.deepSet
import org.apache.commons.text.StringSubstitutor
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AuroraConfigField(
    val sources: Set<AuroraConfigFieldSource>,
    @JsonIgnore
    val replacer: StringSubstitutor = StringSubstitutor()
) {
    val source: String
        get() = sources.last().source

    val isDefault: Boolean
        @JsonIgnore
        get() = sources.last().defaultSource

    val value: JsonNode
        get() = sources.last().value

    inline fun <reified T> getNullableValue(): T? = this.value() as T?

    inline fun <reified T> value(): T {

        val it = sources.last()!!
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
        val valueNode = sources.last()!!.value
        return when {
            valueNode.isTextual -> valueNode.textValue().split(delimiter).toList()
            valueNode.isArray -> (value() as List<Any?>).map { it?.toString() } // Convert any non-string values in the array to string
            else -> emptyList()
        }.filter { !it.isNullOrBlank() }
            .mapNotNull { it?.trim() }
            .toSet()
    }

    @JsonIgnore
    fun isSimplifiedConfig(): Boolean {
        return sources.last()!!.value.isBoolean
    }
}

data class AuroraConfigFieldSource(
    val source: String,
    val value: JsonNode,
    @JsonIgnore
    val defaultSource: Boolean = false
)

class AuroraDeploymentSpec(val fields: Map<String, AuroraConfigField>) {

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
        keyMappingsExtractor?.let { extractOrNull(it.name) }

    fun disabledAndNoSubKeys(name: String): Boolean {

        val simplified = isSimplifiedConfig(name)

        val simplifiedIsDiabled = !extract<Boolean>(name)

        return simplified && simplifiedIsDiabled && noSpecifiedSubKeys(name)
    }

    fun noSpecifiedSubKeys(name: String): Boolean {
        return this.fields.none { it.key.startsWith("$name/") && it.value.source != "default" }
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

    companion object {

        val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentSpec::class.java)

        fun create(
            handlers: Set<AuroraConfigFieldHandler>,
            files: List<AuroraConfigFile>,
            applicationId: ApplicationId,
            configVersion: String,
            placeholders: Map<String, String> = emptyMap()
        ): AuroraDeploymentSpec {

            val mapper = jacksonObjectMapper()

            val staticFields: List<Pair<String, AuroraConfigFieldSource>> =
                listOf(
                    "applicationId" to
                        AuroraConfigFieldSource("static", mapper.convertValue(applicationId.toString())),
                    "configVersion" to
                        AuroraConfigFieldSource("static", mapper.convertValue(configVersion))
                )

            val replacer = StringSubstitutor(placeholders, "@", "@")

            val fields: List<Pair<String, AuroraConfigFieldSource>> = handlers.flatMap { handler ->

                val defaultValue = handler.defaultValue?.let {
                    listOf(
                        handler.name to AuroraConfigFieldSource(
                            source = handler.defaultSource,
                            value = mapper.convertValue(handler.defaultValue),
                            defaultSource = true
                        )
                    )
                } ?: emptyList()

                val result = defaultValue + files.mapNotNull { file ->
                    file.asJsonNode.atNullable(handler.path)?.let {
                        if (handler.subKeyFlag && it.isObject) {
                            null
                        } else {
                            handler.name to AuroraConfigFieldSource(file.configName, it, handler.subKeyFlag)
                        }
                    }
                }
                logger.trace("Processed handler {} result={}", handler.name, result)
                result
            }

            val allFields: List<Pair<String, AuroraConfigFieldSource>> = staticFields + fields

            val groupedFields: Map<String, AuroraConfigField> = allFields
                .groupBy({ it.first }) { it.second }
                .mapValues { AuroraConfigField(it.value.toSet(), replacer) }
            return AuroraDeploymentSpec(groupedFields)
        }
    }

    fun removeDefaults() = AuroraDeploymentSpec(this.fields.filter { it.value.source != "default" })

    fun removeInactive(): AuroraDeploymentSpec {

        fun createExcludePaths(fields: Map<String, AuroraConfigField>): Set<String> {

            return fields
                .filter { it.key.split("/").size == 1 }
                .filter {
                    val value = it.value.value
                    !value.isBoolean || !value.booleanValue()
                }.map {
                    it.key.split("/")[0] + "/"
                }.toSet()
        }

        val excludePaths = createExcludePaths(this.fields)
        return AuroraDeploymentSpec(this.fields.filter { field ->
            excludePaths.none { field.key.startsWith(it) }
        })
    }

    fun present(transformer: (Map.Entry<String, AuroraConfigField>) -> Map<String, Any>): Map<String, Any> {

        val map: MutableMap<String, Any> = mutableMapOf()
        this.fields
            .mapValues { transformer(it) }
            .forEach {
                map.deepSet(it.key.split("/"), it.value)
            }
        return map
    }
}

