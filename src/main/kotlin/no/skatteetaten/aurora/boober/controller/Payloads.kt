package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.SetupParams

data class AuroraConfigPayload(
        val files: Map<String, Map<String, Any?>> = mapOf(),
        val secrets: Map<String, String> = mapOf()
) {
    fun toAuroraConfig(): AuroraConfig {
        val auroraConfigFiles = files.map { AuroraConfigFile(it.key, it.value) }
        return AuroraConfig(auroraConfigFiles, secrets)
    }
}

data class SetupParamsPayload(
        val envs: List<String> = listOf(),
        val apps: List<String> = listOf(),
        val overrides: Map<String, Map<String, Any?>> = mapOf(),
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