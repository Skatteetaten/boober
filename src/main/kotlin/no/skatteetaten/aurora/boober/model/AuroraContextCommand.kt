package no.skatteetaten.aurora.boober.model

import no.skatteetaten.aurora.boober.service.AuroraConfigRef

data class AuroraContextCommand(
    val auroraConfig: AuroraConfig,
    val applicationDeploymentRef: ApplicationDeploymentRef,
    val auroraConfigRef: AuroraConfigRef,
    val overrides: List<AuroraConfigFile> = emptyList()
) {

    val applicationFiles: List<AuroraConfigFile> by lazy {
        auroraConfig.getFilesForApplication(applicationDeploymentRef, overrides)
    }

    val applicationFile: AuroraConfigFile
        get() = applicationFiles.find { it.type == AuroraConfigFileType.APP && !it.override }!!

    val overrideFiles: Map<String, String>
        get() = applicationFiles.filter { it.override }.associate { it.name to it.contents }
}
