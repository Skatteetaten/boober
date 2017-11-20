package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.JsonNode

data class AuroraConfigFieldHandler(val name: String,
                                    val path: String = "/$name",
                                    val validator: (JsonNode?) -> Exception? = { _ -> null },
                                    val defaultValue: String? = null,
                                    val defaultSource: String = "default")
