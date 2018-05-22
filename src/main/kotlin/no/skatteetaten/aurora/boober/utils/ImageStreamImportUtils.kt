package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.model.openshift.ImageStreamImport

fun imageStreamImportFromJson(jsonNode: JsonNode?): ImageStreamImport =
    jacksonObjectMapper().readValue(jsonNode.toString())
