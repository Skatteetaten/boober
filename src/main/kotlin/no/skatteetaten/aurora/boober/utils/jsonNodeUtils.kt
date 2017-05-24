package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

fun JsonNode.updateField(source: JsonNode, root: String, field: String, required: Boolean = false) {
    val sourceField = source.at("$root/$field")

    if (sourceField.isMissingNode) {
        if (required) {
            throw IllegalArgumentException("Field $root/$field is not set in source")
        }
        return
    }

    val targetRoot = this.at(root) as ObjectNode
    if (targetRoot.isMissingNode) {
        throw IllegalArgumentException("Root $root is not set in target")
    }

    targetRoot.set(field, sourceField)
}

fun JsonNode?.startsWith(pattern: String, message: String): Exception? {
    if (this == null) {
        return IllegalArgumentException(message)
    }
    if (!this.textValue().startsWith(pattern)) {
        return IllegalArgumentException(message)

    }

    return null
}
fun JsonNode?.pattern(pattern: String, message: String): Exception? {
    if (this == null) {
        return IllegalArgumentException(message)
    }
    if (!Regex(pattern).matches(this.textValue())) {
        return IllegalArgumentException(message)

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
    if (this == null || this.textValue().isBlank()) {
        return IllegalArgumentException(message)
    }

    return null
}

fun JsonNode?.length(length: Int, message: String): Exception? {
    if (this == null) {
        return IllegalArgumentException(message)
    } else if (this.textValue().length > length) {
        return IllegalArgumentException(message)
    }

    return null
}
