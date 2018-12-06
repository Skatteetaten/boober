package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.mapper.v1.convertValueToString
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType
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

    private val source: AuroraConfigFieldSource get() = sources.last()

    val name: String
        get() = source.configFile.configName

    val isDefault: Boolean
        @JsonIgnore
        get() = source.configFile.type == AuroraConfigFileType.DEFAULT

    val fileType: AuroraConfigFileType get() = source.configFile.type

    val value: JsonNode
        get() = source.value

    inline fun <reified T> getNullableValue(): T? = this.value() as T?

    inline fun <reified T> value(): T {

        val result = jacksonObjectMapper().convertValue(value, T::class.java)
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
        return when {
            value.isTextual -> value.textValue().split(delimiter).toList()
            value.isArray -> (value() as List<Any?>).map { it?.toString() } // Convert any non-string values in the array to string
            else -> emptyList()
        }.filter { !it.isNullOrBlank() }
            .mapNotNull { it?.trim() }
            .toSet()
    }
}

data class AuroraConfigFieldSource(
    val configFile: AuroraConfigFile,
    val value: JsonNode
)

class AuroraDeploymentSpec(val fields: Map<String, AuroraConfigField>) {

    fun getConfigEnv(configExtractors: List<AuroraConfigFieldHandler>): Map<String, String> {
        return configExtractors.filter { it.name.count { it == '/' } == 1 }.associate {
            val (_, field) = it.name.split("/", limit = 2)
            val value: Any = this[it.name]
            val escapedValue: String = convertValueToString(value)
            field to escapedValue
        }
    }

    fun getRouteAnnotations(prefix: String, extractors: List<AuroraConfigFieldHandler>): Map<String, String> {
        return extractors
            .filter { it.path.startsWith("/$prefix") }
            .associate {
                val (_, _, _, field) = it.name.split("/", limit = 4)
                val value: String = this[it.name]
                field to value
            }
    }

    fun getParameters(parameterExtractors: List<AuroraConfigFieldHandler>): Map<String, String>? {
        return parameterExtractors.associate {
            val (_, field) = it.name.split("/", limit = 2)

            val value: String = this[it.name]
            field to value
        }
    }

    fun getKeyMappings(keyMappingsExtractor: AuroraConfigFieldHandler?): Map<String, String>? =
        keyMappingsExtractor?.let { getOrNull(it.name) }

    fun isSimplifiedAndDisabled(name: String): Boolean {
        return isSimplifiedConfig(name) && !get<Boolean>(name)
    }

    fun isSimplifiedAndEnabled(name: String): Boolean {
        return isSimplifiedConfig(name) && get(name)
    }

    /*
    In order to know if this is simplified config or not we need to find out what instruction is
    specified in the most specific place. Each AuroraConfigFieldSource has a a presedence accoring to the
    AuroraConfigFileType enum.
     */
    fun isSimplifiedConfig(name: String): Boolean {
        val field = fields[name]!!

        val subKeys = getSubKeys(name)
        // If there are no subkeys we cannot be complex
        if (subKeys.isEmpty()) {
            return true
        }

        // if any subkey has higher presedence accoring to the AuroraConfigFileTypeEnum we are not simplified
        return !subKeys.any { it.value.fileType > field.fileType }
    }

    fun getSubKeys(name: String): Map<String, AuroraConfigField> {
        val subKeys = fields
            .filter { it.key.startsWith("$name/") }
        return subKeys
    }

    inline operator fun <reified T> get(name: String): T = fields[name]!!.value()

    /**
     * Extracts a config field declared either as a delimited string (ie. "value1, value2") or as a JSON array
     * (ie. ["value1", "value2"]) as a String list.
     */
    fun getDelimitedStringOrArrayAsSet(name: String, delimiter: String = ","): Set<String> {
        return fields[name]?.extractDelimitedStringOrArrayAsSet(delimiter) ?: emptySet()
    }

    inline fun <reified T> getOrNull(name: String): T? = fields[name]?.getNullableValue()

    companion object {

        val logger: Logger = LoggerFactory.getLogger(AuroraDeploymentSpec::class.java)

        fun create(
            handlers: Set<AuroraConfigFieldHandler>,
            files: List<AuroraConfigFile>,
            applicationDeploymentRef: ApplicationDeploymentRef,
            configVersion: String,
            placeholders: Map<String, String> = emptyMap()
        ): AuroraDeploymentSpec {

            val mapper = jacksonObjectMapper()

            val staticFields: List<Pair<String, AuroraConfigFieldSource>> =
                listOf(
                    "applicationDeploymentRef" to
                        AuroraConfigFieldSource(
                            AuroraConfigFile("static", "{}", isDefault = true),
                            mapper.convertValue(applicationDeploymentRef.toString())
                        ),
                    "configVersion" to
                        AuroraConfigFieldSource(
                            AuroraConfigFile("static", "{}", isDefault = true),
                            mapper.convertValue(configVersion)
                        )
                )

            val replacer = StringSubstitutor(placeholders, "@", "@")

            val fields: List<Pair<String, AuroraConfigFieldSource>> = handlers.flatMap { handler ->
                val defaultValue = handler.defaultValue?.let {
                    listOf(
                        handler.name to AuroraConfigFieldSource(
                            configFile = AuroraConfigFile(
                                name = handler.defaultSource,
                                contents = "",
                                isDefault = true
                            ),
                            value = mapper.convertValue(handler.defaultValue)
                        )
                    )
                } ?: emptyList()

                val result = defaultValue + files.mapNotNull { file ->
                    file.asJsonNode.atNullable(handler.path)?.let {
                        /*
                          If a handler can be simplified or complex we do not create
                          a source if it is complex

                          The indidividual subKeys have their own handlers.
                         */
                        if (handler.canBeSimplifiedConfig && it.isObject) {
                            null
                        } else {
                            handler.name to AuroraConfigFieldSource(configFile = file, value = it)
                        }
                    }
                }
                result
            }

            val allFields: List<Pair<String, AuroraConfigFieldSource>> = staticFields + fields

            val groupedFields: Map<String, AuroraConfigField> = allFields
                .groupBy({ it.first }) { it.second }
                .mapValues { AuroraConfigField(it.value.toSet(), replacer) }
            return AuroraDeploymentSpec(groupedFields)
        }
    }

    fun removeDefaults() = AuroraDeploymentSpec(this.fields.filter { it.value.name != "default" })

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

    fun <T> featureEnabled(name: String, fn: (String) -> T?): T? {

        // feature is disabled and has no specified subKeys
        // adding a sub key implicitly enables a feature
        if (isSimplifiedAndDisabled(name)) {
            return null
        }

        return fn(name)
    }
}
