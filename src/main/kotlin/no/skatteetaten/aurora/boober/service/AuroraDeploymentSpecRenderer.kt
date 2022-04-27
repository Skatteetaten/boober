package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.deepSet

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

    val maxSource: Int = sources.map { it.source.length }.maxOrNull() ?: 0

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
fun AuroraDeploymentSpec.present(
    includeDefaults: Boolean = true,
    transformer: (Map.Entry<String, AuroraConfigField>) -> Map<String, Any>
): Map<String, Any> {

    val excludePaths = this.fields.filter { isSimplifiedAndDisabled(it.key) }.map { "${it.key}/" }
    val map: MutableMap<String, Any> = mutableMapOf()
    this.fields
        .filter { field ->
            val simpleCheck = if (field.value.canBeSimplified) {
                this.isSimplifiedConfig(field.key)
            } else {
                true
            }

            val defaultCheck = if (!includeDefaults) {
                field.value.name != "default"
            } else {
                true
            }

            val excludeCheck = excludePaths.none { field.key.startsWith(it) }

            simpleCheck && defaultCheck && excludeCheck
        }
        .mapValues { transformer(it) }
        .forEach {
            map.deepSet(it.key.split("/"), it.value)
        }
    return map
}
