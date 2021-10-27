package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FilenameUtils
import org.springframework.util.Base64Utils
import java.util.regex.Pattern

fun String.ensureEndsWith(endsWith: String, seperator: String = ""): String {
    if (this.endsWith(endsWith)) {
        return this
    }
    return "$this$seperator$endsWith"
}

/**
 * If the string contains more than a given max length the string will be truncated and a 7 char hash based on trailing characters will be added.
 * The end result will be maxLength. If the string is shorter than max length the same string will be returned.
 */
fun String.truncateStringAndHashTrailingCharacters(maxLength: Int, delimiter: Char? = '-'): String {
    if (this.length <= maxLength) {
        return this
    }

    val textLength = if (delimiter == null) {
        maxLength - 7
    } else {
        maxLength - 8
    }

    val overflow = this.substring(textLength)
    return this.substring(0, textLength) + (delimiter ?: "") + DigestUtils.sha1Hex(overflow).take(7)
}

// A kubernetes name must lowercase and cannot contain _.
fun String.normalizeKubernetesName() = this.lowercase().replace("_", "-")

fun String.ensureStartWith(startWith: String, seperator: String = ""): String {
    if (this.startsWith(startWith)) {
        return this
    }
    return "$startWith$seperator$this"
}

/** Inspired by https://www.geeksforgeeks.org/how-to-validate-a-domain-name-using-regular-expression/
 * but trimmed down due to local needs
 */
private val dnsMatcher: Pattern = Pattern.compile(
    "^((?!-)[A-Za-z0-9-]" +
        "{1,63}(?<!-))"
)

fun String.isValidDns(): Boolean {
    return this
        .split(".")
        .all { dnsMatcher.matcher(it).matches() } && this.length < 254
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

fun String.base64Decode() = String(Base64Utils.decodeFromString(this))

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

fun convertValueToString(value: Any): String {
    return when (value) {
        is String -> value
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> jacksonObjectMapper().writeValueAsString(value)
    }
}

fun String.dockerGroupSafeName() = this.replace(".", "_")
