package no.skatteetaten.aurora.boober.mapper

import com.fasterxml.jackson.databind.JsonNode

class AuroraConfigFieldHandler(val name: String,
                               val path: String = "/$name",
                               val validator: (JsonNode?) -> Exception? = { _ -> null },
                               val defaultValue: String? = null)
