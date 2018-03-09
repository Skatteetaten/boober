package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.fabric8.openshift.api.model.ImageStream

fun ImageStream.findCurrentImageHash(): String? =
        this.status?.tags?.firstOrNull()?.items?.firstOrNull()?.image


fun ImageStream.findDockerImageUrl(tagName: String): String? =
        this.spec?.tags?.firstOrNull { it.name == tagName }?.from?.name


fun imageStreamFromJson(jsonNode: JsonNode?): ImageStream =
        jacksonObjectMapper().readValue(jsonNode.toString())
