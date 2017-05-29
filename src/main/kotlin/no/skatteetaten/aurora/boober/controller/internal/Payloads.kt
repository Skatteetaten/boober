package no.skatteetaten.aurora.boober.controller.internal

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.DeployCommand

typealias JsonDataFiles = Map<String, JsonNode>

data class AuroraConfigPayload(
        val files: JsonDataFiles = mapOf(),
        val secrets: Map<String, String> = mapOf()
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
        val overrides: JsonDataFiles = mapOf()
) {
    fun toSetupParams(): SetupParams {

        val overrideFiles = overrides.map { AuroraConfigFile(it.key, it.value, true) }.toMutableList()
        return SetupParams(envs, apps, overrideFiles)
    }
}

data class SetupCommand(val affiliation: String,
                        val auroraConfig: AuroraConfigPayload? = null,
                        val setupParams: SetupParamsPayload
)

data class SetupParams(
        val envs: List<String> = listOf(),
        val apps: List<String> = listOf(),
        val overrides: MutableList<AuroraConfigFile> = mutableListOf()
) {
    val applicationIds: List<DeployCommand>
        get() = envs.flatMap { env -> apps.map { app -> DeployCommand(ApplicationId(env, app), overrides) } }
}
