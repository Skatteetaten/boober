package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

fun renderJsonFromAuroraDeploymentSpec(deploymentSpec: AuroraDeploymentSpec): Map<String, Any?> {

    val entriesWithMultiValues = listOf("route", "webseal", "database", "certificate", "prometheus",
            "management", "readiness", "liveness")

    val resultMap = mutableMapOf<String, Any?>()

    deploymentSpec.fields.entries.forEach { entry ->

        val keys = entry.key.split("/")
        var next = resultMap

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

    return resultMap.filter {
        val rootKey = it.key.removePrefix("/")

        if (!entriesWithMultiValues.contains(rootKey)) {
            return@filter true
        }

        val map = it.value as Map<String, Any>
        if (map.containsKey("value")) {
            val value = map["value"] as JsonNode
            return@filter value.asText() != "false"
        }

        return@filter true
    }
}
