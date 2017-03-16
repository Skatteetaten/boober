package no.skatteetaten.aurora.boober.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeType
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

fun mergeJsonFiles(jsonFiles: List<String>): String {
    val mapper = jacksonObjectMapper()
    val mergedJsonFields = jsonFiles.map(mapper::readTree).reduce(::reduceJsonFields)
    mergedJsonFields.toString().let(::println)
    return mergedJsonFields.toString()
}

fun reduceJsonFields(result: JsonNode, current: JsonNode): JsonNode {
    result as ObjectNode
    current.fieldNames().forEach {
        when(current.get(it).nodeType) {
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