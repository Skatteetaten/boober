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
                val targetProperty = targetNode[key] as MutableMap<String, Any?>?
                targetProperty?.putAll(value as Map<String, Any?>) ?: targetNode.put(key, value)
            }
            else -> {
                targetNode.put(key, value)
            }
        }
    }

    return targetNode
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