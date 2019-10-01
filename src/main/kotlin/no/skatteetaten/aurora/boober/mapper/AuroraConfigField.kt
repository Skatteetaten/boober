package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType
import org.apache.commons.text.StringSubstitutor

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AuroraConfigField(
    val sources: Set<AuroraConfigFieldSource>,
    @JsonIgnore
    val replacer: StringSubstitutor = StringSubstitutor()
) {

    private val source: AuroraConfigFieldSource get() = sources.last()

    val canBeSimplified: Boolean
        get() = source.canBeSimplified

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
            .map { replacer.replace(it) }
            .toSet()
    }
}

data class AuroraConfigFieldSource(
    val configFile: AuroraConfigFile,
    val value: JsonNode,
    val canBeSimplified: Boolean = false
)
