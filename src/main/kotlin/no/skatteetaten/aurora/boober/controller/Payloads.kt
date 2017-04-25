package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.SetupParams

typealias JsonDataFiles = Map<FileName, JsonData>

data class AuroraConfigPayload(
        val files: JsonDataFiles = mapOf(),
        val secrets: TextFiles = mapOf()
) {
    fun toAuroraConfig(): AuroraConfig {
        val auroraConfigFiles = files.map { AuroraConfigFile(it.key, it.value) }
        return AuroraConfig(auroraConfigFiles, secrets)
    }
}

fun fromAuroraConfig(auroraConfig: AuroraConfig): AuroraConfigPayload {

    val files: JsonDataFiles = auroraConfig.auroraConfigFiles.associate { it.name to it.contents }
    return AuroraConfigPayload(files, auroraConfig.secrets)
}

data class SetupParamsPayload(
        val envs: List<String> = listOf(),
        val apps: List<String> = listOf(),
        val overrides: JsonDataFiles = mapOf(),
        val dryRun: Boolean = false
) {
    fun toSetupParams(): SetupParams {

        return SetupParams(envs, apps, overrides.map { AuroraConfigFile(it.key, it.value) }, dryRun)
    }
}

data class SetupCommand(val affiliation: String,
                        val auroraConfig: AuroraConfigPayload? = null,
                        val setupParams: SetupParamsPayload
)
