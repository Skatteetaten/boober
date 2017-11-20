package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.mapper.AuroraConfigField
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

fun createMapForAuroraDeploymentSpecPointers(deploymentSpec: AuroraDeploymentSpec, includeDefaults: Boolean = true): Map<String, Any?> {
    val fields = mutableMapOf<String, Any?>()
    val includeSubKeys = createIncludeSubKeysMap(deploymentSpec)

    val missingFields: MutableMap<String, AuroraConfigField> = mutableMapOf()

    deploymentSpec.build?.let {
        if (!deploymentSpec.fields.containsKey("baseImage/name")) {
            missingFields.put("baseImage/name", AuroraConfigField(AuroraConfigFieldHandler("baseImage/name", defaultValue = it.applicationPlatform.baseImageName)))
        }
        if (!deploymentSpec.fields.containsKey("baseImage/version")) {
            missingFields.put("baseImage/version", AuroraConfigField(AuroraConfigFieldHandler("baseImage/version", defaultValue = it.applicationPlatform.baseImageVersion)))
        }
    }
    (deploymentSpec.fields + missingFields).entries.forEach { entry ->

        val configField = entry.value
        val configPath = entry.key

        if (configField.value is ObjectNode) {
            return@forEach
        }

        if (configField.handler.defaultValue != null && !includeDefaults) {
            return@forEach
        }

        val keys = configPath.split("/")
        if (keys.size > 1 && !includeSubKeys.getOrDefault(keys[0], true)) {
            return@forEach
        }

        var next = fields
        keys.forEachIndexed { index, key ->
            if (index == keys.lastIndex) {
                next[key] = mutableMapOf(
                        "source" to configField.source!!.configName,
                        "value" to configField.value
                )
            } else {
                if (next[key] == null) {
                    next[key] = mutableMapOf<String, Any?>()
                }

                if (next[key] is MutableMap<*, *>) {
                    next = next[key] as MutableMap<String, Any?>
                }
            }
        }
    }

    return fields
}

@JvmOverloads
fun renderJsonForAuroraDeploymentSpecPointers(deploymentSpec: AuroraDeploymentSpec, includeDefaults: Boolean): String {

    val fields = createMapForAuroraDeploymentSpecPointers(deploymentSpec, includeDefaults)
    val defaultKeys = listOf("source", "value")
    val indentLength = 2
    val (keyMaxLength, valueMaxLength) = findMaxKeyAndValueLength(deploymentSpec.fields, indentLength)

    fun renderJson(level: Int, result: String, entry: Map.Entry<String, Map<String, Any?>>): String {

        val key = entry.key
        val value = entry.value["value"].toString()
        val source = entry.value["source"].toString()
        val indents = " ".repeat(level * indentLength)

        return if (entry.value.keys.all { defaultKeys.indexOf(it) != -1 }) {
            val keySpaces = " ".repeat(keyMaxLength + 2 - key.length - level * 2)
            val valueSpaces = " ".repeat(valueMaxLength + 1 - value.length)

            "$result$indents$key:$keySpaces$value$valueSpaces// $source\n"
        } else {
            val nextObject = indents + "$key: {\n"
            val nextObjectResult = entry.value
                    .entries
                    .filter { defaultKeys.indexOf(it.key) == -1 }
                    .fold(nextObject) { res, e ->
                        res + renderJson(level + 1, "", e as Map.Entry<String, Map<String, Any?>>)
                    }
            result + nextObjectResult + indents + "}\n"
        }
    }

    return fields.entries
            .map { it as Map.Entry<String, Map<String, Any?>> }
            .fold("{\n") { result, entry ->
                renderJson(1, result, entry)
            } + "}"

}

private fun createIncludeSubKeysMap(deploymentSpec: AuroraDeploymentSpec): Map<String, Boolean> {

    val includeSubKeys = mutableMapOf<String, Boolean>()

    deploymentSpec.fields.entries
            .filter { it.key.split("/").size == 1 }
            .forEach {
                val key = it.key.split("/")[0]
                val shouldIncludeSubKeys = it.value.value.textValue() != "false"
                includeSubKeys.put(key, shouldIncludeSubKeys)
            }

    return includeSubKeys
}

private fun findMaxKeyAndValueLength(fields: Map<String, AuroraConfigField>, indentLength: Int): Pair<Int, Int> {
    var keyMaxLength = 0
    var valueMaxLength = 0

    fields.entries.forEach {
        val configValue = it.value.value.toString()
        if (configValue.length > valueMaxLength) {
            valueMaxLength = configValue.length
        }

        it.key.split("/").forEachIndexed { i, k ->
            val key = k.length + indentLength * i + 1
            if (key > keyMaxLength) {
                keyMaxLength = key
            }
        }
    }

    return Pair(keyMaxLength, valueMaxLength)
}
