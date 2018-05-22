package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.spring.autoconfigure.export.StringToDurationConverter

fun JsonNode.findAllPointers(maxLevel: Int): List<String> {

    fun inner(root: String, node: ObjectNode): List<String> {

        if (root.startsWith("/mount") && root.split("/").size > maxLevel) {
            return listOf(root)
        }
        val ret = mutableListOf<String>()
        for ((key, child) in node.fields()) {
            val newKey = "$root/$key"
            if (child is ObjectNode) {
                ret += inner(newKey, child)
            } else {
                ret += newKey
            }
        }
        return ret
    }

    if (this is ObjectNode) {
        return inner("", this)
    } else {
        return listOf()
    }
}

val JsonNode.openshiftKind: String
    get() = this.get("kind")?.asText()?.toLowerCase()
        ?: throw IllegalArgumentException("Kind must be set in file=$this")

val JsonNode.openshiftName: String
    get() = when (this.openshiftKind) {
        "deploymentrequest" -> this.get("name")?.asText()
            ?: throw IllegalArgumentException("name not specified for resource kind=${this.openshiftKind}")
        else -> this.get("metadata")?.get("name")?.asText()
            ?: throw IllegalArgumentException("name not specified for resource kind=${this.openshiftKind}")
    }

fun JsonNode.updateField(source: JsonNode, root: String, field: String, required: Boolean = false) {
    val sourceField = source.at("$root/$field")

    if (sourceField.isMissingNode) {
        if (required) {
            throw IllegalArgumentException("Field $root/$field is not set in source")
        }
        return
    }

    val targetRoot = this.at(root)
    if (targetRoot.isMissingNode) {
        if (required) {
            throw IllegalArgumentException("Root $root is not set in target")
        } else {
            return
        }
    }

    (targetRoot as ObjectNode).set(field, sourceField)
}

fun JsonNode.mergeField(source: ObjectNode, root: String, field: String) {
    val jsonPtrExpr = "$root/$field"
    val sourceObject = source.at(jsonPtrExpr) as ObjectNode

    val mergedObject = sourceObject.deepCopy()
    this.at(jsonPtrExpr)
        .takeIf { it is ObjectNode }
        ?.also { mergedObject.setAll(it as ObjectNode) }

    (this.at(root) as ObjectNode).set(field, mergedObject)
}

fun JsonNode?.startsWith(pattern: String, message: String): Exception? {
    if (this == null || !this.isTextual) {
        return IllegalArgumentException(message)
    }
    if (!this.textValue().startsWith(pattern)) {
        return IllegalArgumentException(message)
    }

    return null
}

fun JsonNode?.pattern(pattern: String, message: String, required: Boolean = true): Exception? {
    if (this == null || !this.isTextual) {
        return if (required) {
            IllegalArgumentException(message)
        } else {
            null
        }
    }
    if (!Regex(pattern).matches(this.textValue())) {
        return IllegalArgumentException(message)
    }

    return null
}

fun JsonNode?.durationString(): Exception? {
    if (this == null || !this.isTextual) {
        return null
    }

    StringToDurationConverter().convert(this.textValue())
    return null
}

fun JsonNode?.oneOf(candidates: List<String>): Exception? {
    if (this == null || !this.isTextual) {
        return IllegalArgumentException("Must be one of [" + candidates.joinToString() + "]")
    }
    if (!candidates.contains(this.textValue())) {
        return IllegalArgumentException("Must be one of [" + candidates.joinToString() + "]")
    }
    return null
}

fun JsonNode?.required(message: String): Exception? {
    if (this == null) {
        return IllegalArgumentException(message)
    }
    return null
}

fun JsonNode?.notBlank(message: String): Exception? {
    if (this == null || !this.isTextual || this.textValue().isBlank()) {
        return IllegalArgumentException(message)
    }

    return null
}

fun JsonNode?.length(length: Int, message: String, required: Boolean = true): Exception? {
    if (this == null || !this.isTextual) {
        return if (required) {
            IllegalArgumentException(message)
        } else {
            null
        }
    } else if (this.textValue().length > length) {
        return IllegalArgumentException(message)
    }

    return null
}

fun jacksonYamlObjectMapper(): ObjectMapper = ObjectMapper(YAMLFactory().enable(JsonParser.Feature.ALLOW_COMMENTS)).registerKotlinModule()

fun jsonMapper(): ObjectMapper = jacksonObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS)
