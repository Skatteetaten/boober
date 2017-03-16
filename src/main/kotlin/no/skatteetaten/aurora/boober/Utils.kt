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


/*
  Reducer to join JsonNodes
 */
fun reduceJsonNodes(acc: JsonNode, current: JsonNode): JsonNode {
    val result: ObjectNode = acc.deepCopy()
    current.fieldNames().forEach {
        when (current.get(it).nodeType) {
            JsonNodeType.OBJECT -> {
                if (result.has(it)) (result.get(it) as ObjectNode).setAll(current.get(it) as ObjectNode)
                else result.set(it, current.get(it))
            }
            else -> {
                result.replace(it, current.get(it))
            }
        }
    }

    return result
}