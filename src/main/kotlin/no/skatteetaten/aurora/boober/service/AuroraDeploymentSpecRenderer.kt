package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec


@JvmOverloads
fun renderJsonForAuroraDeploymentSpecPointers(deploymentSpec: AuroraDeploymentSpec, includeDefaults: Boolean): String {

    val fields = deploymentSpec.fields
    val defaultKeys = listOf("source", "value")
    val (keyMaxLength, valueMaxLength, indent) = deploymentSpec.formatting

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

    return fields.entries
            .filter {
                if (!includeDefaults) {
                    it.value["source"].toString() != "default"
                } else {
                    true
                }
            }
            .fold("{\n") { result, entry ->
                renderJson(1, result, entry)
            } + "}"

}



