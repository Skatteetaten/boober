package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.commons.io.FilenameUtils

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

private const val base64Prefix = "data:application/json;base64,"

fun String.isBase64() = this.startsWith(base64Prefix)

fun String.withBase64Prefix() = "$base64Prefix$this"

fun String.withoutBase64Prefix() = this.removePrefix(base64Prefix)