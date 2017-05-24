package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.utils.SampleFilesCollector

fun getAuroraConfigSamples(secrets: Map<String, String>): AuroraConfig {
    val folder = SampleFilesCollector.sampleConfigDir()

    val files = folder.walkBottomUp()
            .onEnter { it.name != "secret" }
            .filter { it.isFile }
            .associate { it.relativeTo(folder).path to it }

    val nodes = files.map {
        it.key to SampleFilesCollector.convertFileToJsonNode(it.value)
    }.toMap()

    return AuroraConfig(nodes.map { AuroraConfigFile(it.key, it.value, false) }, secrets)
}

fun getAuroraConfigSamples(): AuroraConfig {
    return getAuroraConfigSamples(mapOf())
}

fun createAuroraConfig(aid: DeployCommand, secrets: Map<String, String>): AuroraConfig {
    val files = SampleFilesCollector.getSampleFiles(aid)
    return AuroraConfig(files.map { AuroraConfigFile(it.key, it.value, false) }, secrets)
}

fun createAuroraConfig(aid: DeployCommand): AuroraConfig {
    return createAuroraConfig(aid, mapOf())
}
