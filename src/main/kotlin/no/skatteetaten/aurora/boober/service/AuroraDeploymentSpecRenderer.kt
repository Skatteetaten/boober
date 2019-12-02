package no.skatteetaten.aurora.boober.service

import java.lang.Integer.max
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.utils.addIfNotNull

data class SpecLine(val source: String, val indent: Int, val content: String)
fun renderJsonForAuroraDeploymentSpecPointers(deploymentSpec: AuroraDeploymentSpec, includeDefaults: Boolean): String {

    val fields = renderSpecAsJson(deploymentSpec, includeDefaults)

    val defaultKeys = listOf("source", "value", "sources")
    val indent = 2

    fun renderJson(level: Int, entry: Map.Entry<String, Map<String, Any?>>): List<SpecLine> {

        val key = entry.key
        val value = entry.value["value"].toString()
        val source = entry.value["source"].toString()

        return if (entry.value.keys.contains("value")) {
            listOf(SpecLine(source, level, "$key: $value"))
        } else {

            val nextObject = SpecLine("", level, "$key:")

            val nextObjectResult = entry.value
                .entries
                .filter { defaultKeys.indexOf(it.key) == -1 }
                .flatMap {
                    renderJson(level + indent, it as Map.Entry<String, Map<String, Any?>>)
                }
            listOf(nextObject).addIfNotNull(nextObjectResult)
        }
    }

    val sources: List<SpecLine> = fields.flatMap { it ->
        renderJson(0, it as Map.Entry<String, Map<String, Any?>>)
    }

    val maxSource: Int = sources.map { it.source.length }.max() ?: 0

    val lines = sources.map { line ->
        val whitespace = "".padStart(line.indent)
        "${line.source.padStart(maxSource)} | ${whitespace}${line.content}"
    }

    return lines.joinToString("\n")
}

fun renderSpecAsJson(deploymentSpec: AuroraDeploymentSpec, includeDefaults: Boolean): Map<String, Any> {

    return deploymentSpec.present(includeDefaults) { field ->
        mapOf(
            "source" to field.value.name,
            "value" to field.value.value,
            "sources" to field.value.sources.map { mapOf("name" to it.configFile.configName, "value" to it.value) }
        )
    }
}
