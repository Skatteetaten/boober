package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.mapper.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec


fun renderJsonForAuroraDeploymentSpecPointers(deploymentSpec: AuroraDeploymentSpec, includeDefaults: Boolean): String {

    val fields = deploymentSpec.fields
    val defaultKeys = listOf("source", "value")
    //TODO rewrite
    val (keyMaxLength, valueMaxLength, indent) = Triple(20, 20, 2)

    fun renderJson(level: Int, result: String, entry: Map.Entry<String, Map<String, Any?>>): String {

        val key = entry.key
        val value = entry.value["value"].toString()
        val source = entry.value["source"].toString()
        val indents = " ".repeat(level * indent)

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


    val filteredFields = if (includeDefaults) fields else filterDefaultFields(fields)


    return filteredFields.entries
            .fold("{\n") { result, entry ->
                renderJson(1, result, entry)
            } + "}"

}

private fun findMaxKeyAndValueLength(fields: Map<String, AuroraConfigField>, indentLength: Int): Triple<Int, Int, Int> {
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

    return Triple(keyMaxLength, valueMaxLength, indentLength)
}


fun filterDefaultFields(fields: Map<String, Map<String, Any?>>): Map<String, Map<String, Any?>> {

    return fields
            .filter {
                it.value["source"].toString() != "default"
            }
            .mapValues {
                if (it.value.containsKey("source")) {
                    it.value
                } else {
                    filterDefaultFields(it.value as Map<String, Map<String, Any?>>)
                }
            }.filter {
                !it.value.isEmpty()
            }
    
}



