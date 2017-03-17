package no.skatteetaten.aurora.boober

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeType
import com.fasterxml.jackson.databind.node.ObjectNode


inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {

    var currentThrowable: java.lang.Throwable? = null
    try {
        return block(this)
    } catch (throwable: Throwable) {
        currentThrowable = throwable as java.lang.Throwable
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
 * Reducer to join JsonNodes
 * Merging ObjectNodes and overrides everything else
 */
fun reduceJsonNodes(accNode: JsonNode, currentNode: JsonNode): JsonNode {
    val result: ObjectNode = accNode.deepCopy()
    currentNode.fieldNames().forEach { field ->
        val currentValue: JsonNode = currentNode.get(field).deepCopy()
        when (currentValue) {
            is ObjectNode -> {
                val resultValue = result.get(field) as ObjectNode?
                resultValue?.setAll(currentValue) ?: result.set(field, currentValue)
            }
            else -> {
                result.replace(field, currentValue)
            }
        }
    }

    return result
}