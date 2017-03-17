package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode


inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {

    var currentThrowable: Throwable? = null
    try {
        return block(this)
    } catch (throwable: Throwable) {
        currentThrowable = throwable
        throw throwable
    } finally {
        if (currentThrowable != null) {
            try {
                this.close()
            } catch (throwable: Throwable) {
                currentThrowable.addSuppressed(throwable)
            }
        } else {
            this.close()
        }
    }
}


/**
 * Creates a copy of node1 and copies (and potentially overwriting) all properties from node2 into it.
 * @param node1
 * @param node2
 */
fun createMergeCopy(node1: JsonNode, node2: JsonNode): JsonNode {
    val mergeTarget: ObjectNode = node1.deepCopy()
    return copyJsonProperties(mergeTarget, node2)
}

fun copyJsonProperties(targetNode: ObjectNode, sourceNode: JsonNode): ObjectNode {
    sourceNode.fieldNames().forEach { field ->
        val sourceProperty: JsonNode = sourceNode.get(field).deepCopy()
        when (sourceProperty) {
            is ObjectNode -> {
                val targetProperty = targetNode.get(field) as ObjectNode?
                targetProperty?.setAll(sourceProperty) ?: targetNode.set(field, sourceProperty)
            }
            else -> {
                targetNode.replace(field, sourceProperty)
            }
        }
    }

    return targetNode
}