package no.skatteetaten.aurora.boober.controller.internal

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile

typealias JsonDataFiles = Map<String, JsonNode>

data class AuroraConfigPayload(
        val files: JsonDataFiles = mapOf(),
        val versions: Map<String, String?> = mapOf()
) {
    fun toAuroraConfig(affiliation: String): AuroraConfig {
        val auroraConfigFiles = files.map { AuroraConfigFile(it.key, it.value, version = versions[it.key]) }
        return AuroraConfig(auroraConfigFiles, affiliation)
    }
}

fun fromAuroraConfig(auroraConfig: AuroraConfig): AuroraConfigPayload {

    val files: JsonDataFiles = auroraConfig.auroraConfigFiles.associate { it.name to it.contents }
    val versions = auroraConfig.auroraConfigFiles.associate { it.name to it.version }
    return AuroraConfigPayload(files, versions = versions)
}

data class SetupParamsPayload(
        val envs: List<String> = listOf(),
        val apps: List<String> = listOf(),
        val overrides: JsonDataFiles = mapOf(),
        val deploy: Boolean = true
) {
    fun toSetupParams(): SetupParams {

        val overrideFiles = overrides.map { AuroraConfigFile(it.key, it.value, true) }.toMutableList()
        return SetupParams(envs, apps, overrideFiles, deploy)
    }
}

data class SetupCommand(val affiliation: String,
                        val setupParams: SetupParamsPayload
)

data class SetupParams(
        val envs: List<String> = listOf(),
        val apps: List<String> = listOf(),
        val overrides: MutableList<AuroraConfigFile> = mutableListOf(),
        val deploy: Boolean
) {
    val applicationIds: List<ApplicationId>
        get() = envs.flatMap { env -> apps.map { app -> ApplicationId(env, app) } }
}
