package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.commons.io.FilenameUtils
import org.springframework.util.Base64Utils

fun String.ensureEndsWith(endsWith: String, seperator: String = ""): String {
    if (this.endsWith(endsWith)) {
        return this
    }
    return "$this$seperator$endsWith"
}

fun String.ensureStartWith(startWith: String, seperator: String = ""): String {
    if (this.startsWith(startWith)) {
        return this
    }
    return "$startWith$seperator$this"
}

fun String.removeExtension(): String = FilenameUtils.removeExtension(this)

fun <R> String.withNonBlank(block: (String) -> R?): R? {

    if (this.isBlank()) {
        return null
    }
    return block(this)
}

fun String.toJson() = jacksonObjectMapper().readValue<JsonNode>(this)

const val base64Prefix = "data:application/json;base64,"

fun String.toBase64() = "$base64Prefix${Base64Utils.encodeToString(this.toByteArray())}"

fun String.base64ToJsonNode() =
    if (this.startsWith(base64Prefix)) {
        val decodedValue = String(Base64Utils.decodeFromString(this.removePrefix(base64Prefix)))
        try {
            jacksonObjectMapper().readValue<JsonNode>(decodedValue)
        } catch (e: JsonParseException) {
            TextNode(decodedValue)
        }
    } else {
        TextNode(this)
    }