package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode

data class AuroraConfigFieldHandler(
    val name: String,
    val path: String = "/$name",
    // Dirty quick fix. This class should never be directly serialized to the http response.
    @JsonIgnore val validator: (JsonNode?) -> Exception? = { _ -> null },
    val defaultValue: Any? = null,
    val defaultSource: String = "default",
    val subKeyFlag: Boolean = false
)
