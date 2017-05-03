package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode


/**
 * Creates a copy of node1 and copies (and potentially overwriting) all properties from node2 into it.
 * @param node1
 * @param node2
 */
fun createMergeCopy(node1: Map<String, Any?>, node2: Map<String, Any?>): Map<String, Any?> {
    val mergeTarget: MutableMap<String, Any?> = HashMap(node1)
    return copyJsonProperties(mergeTarget, node2)
}

fun copyJsonProperties(targetNode: MutableMap<String, Any?>, sourceNode: Map<String, Any?>): Map<String, Any?> {
    sourceNode.forEach { (key, value) ->
        when (value) {
            is Map<*, *> -> {
                val targetChildNode = copyChildNode(key, targetNode)

                if (targetChildNode != null) {
                    targetChildNode.putAll(value as Map<String, Any?>)
                    targetNode.replace(key, targetChildNode)
                } else {
                    targetNode.put(key, value)
                }
            }
            else -> {
                targetNode.put(key, value)
            }
        }
    }

    return targetNode
}


private fun copyChildNode(key: String, targetNode: MutableMap<String, Any?>): HashMap<String, Any?>? {
    return if (targetNode.containsKey(key)) HashMap(targetNode[key] as MutableMap<String, Any?>)
    else null
}

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

