package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.DeployCommand
import java.io.File

class AuroraConfigHelper

val folder = File(AuroraConfigHelper::class.java.getResource("/samples/config").file)

@JvmOverloads
fun getAuroraConfigSamples(secrets: Map<String, String> = mapOf()): AuroraConfig {
    val files = folder.walkBottomUp()
            .onEnter { it.name != "secret" }
            .filter { it.isFile }
            .associate { it.relativeTo(folder).path to it }

    val nodes = files.map {
        it.key to convertFileToJsonNode(it.value)
    }.toMap()

    return AuroraConfig(nodes.map { AuroraConfigFile(it.key, it.value!!, false) }, secrets)
}


@JvmOverloads
fun createAuroraConfig(aid: DeployCommand, secrets: Map<String, String> = mapOf()): AuroraConfig {
    val files = getSampleFiles(aid)

    return AuroraConfig(files.map { AuroraConfigFile(it.key, it.value!!, false) }, secrets)
}

fun getSampleFiles(deployCommand: DeployCommand): Map<String, JsonNode?> {
    val applicationName = deployCommand.applicationName
    val environmentName = deployCommand.environmentName

    return collectFilesToMapOfJsonNode(
            "about.json",
            "$applicationName.json",
            "$environmentName/about.json",
            "$environmentName/$applicationName.json"
    )
}

private fun collectFilesToMapOfJsonNode(vararg fileNames: String): Map<String, JsonNode?> {

    return fileNames.map { it to convertFileToJsonNode(File(folder, it)) }.toMap()
}

private fun convertFileToJsonNode(file: File): JsonNode? {

    val mapper = Configuration().mapper()
    return mapper.readValue(file, JsonNode::class.java)
}

