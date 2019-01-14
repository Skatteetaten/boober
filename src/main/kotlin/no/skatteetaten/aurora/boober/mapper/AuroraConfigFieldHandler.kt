package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode

/*
 Some configField handlers can have sub keys but not be simplified config. So we need a boolean toggle for it.
 */

typealias Validator = (JsonNode?) -> Exception?

val defaultValidator: Validator = { null }

data class AuroraConfigFieldHandler(
    val name: String,
    val path: String = "/$name",
    // Dirty quick fix. This class should never be directly serialized to the http response.
    @JsonIgnore val validator: Validator = defaultValidator,
    val defaultValue: Any? = null,
    val defaultSource: String = "default",
    val canBeSimplifiedConfig: Boolean = false
)
