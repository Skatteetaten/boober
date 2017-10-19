package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

fun createMapForAuroraDeploymentSpecPointers(deploymentSpec: AuroraDeploymentSpec): Map<String, Any?> {
    val fields = mutableMapOf<String, Any?>()

    deploymentSpec.fields.entries.forEach { entry ->

        val keys = entry.key.split("/")
        var next = fields

        if (entry.value.value is ObjectNode) {
            return@forEach
        }

        keys.forEachIndexed { index, key ->
            if (index == keys.lastIndex) {
                next[key] = mutableMapOf(
                        "source" to entry.value.source,
                        "value" to entry.value.value
                )
            } else {
                if (next[key] == null) {
                    next[key] = mutableMapOf<String, Any?>()
                }

                next = when (next[key]) {
                    is MutableMap<*, *> -> next[key] as MutableMap<String, Any?>
                    else -> mutableMapOf()
                }
            }
        }
    }

    return fields.filter {
        val map = it.value as Map<String, Any>
        if (map.containsKey("value")) {
            val value = map["value"] as JsonNode
            return@filter !(value.asText() == "false" && map["source"] == "default")
        }

        return@filter true
    }
}
