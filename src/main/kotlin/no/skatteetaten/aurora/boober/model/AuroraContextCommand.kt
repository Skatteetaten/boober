package no.skatteetaten.aurora.boober.model

import no.skatteetaten.aurora.boober.service.AuroraConfigRef

data class AuroraContextCommand(
    //OVERFORING: denne er kun referert for å finne applicationFiles, templateFile og ref. Trenger kanskje ikke hver her da, den er jo litt stor.
    val auroraConfig: AuroraConfig,
    val applicationDeploymentRef: ApplicationDeploymentRef,
    val auroraConfigRef: AuroraConfigRef,
    val overrides: List<AuroraConfigFile> = emptyList(),
    val errorsAsWarnings: Boolean = false
) {

    // OVERFORING man kan argumentere for at hver context har alle filer er dumt. I tilegg til disse filene må local Template feature ha tilgang på templates mappen
    val applicationFiles: List<AuroraConfigFile> by lazy {
        auroraConfig.getFilesForApplication(applicationDeploymentRef, overrides)
    }

    val applicationFile: AuroraConfigFile
        get() = applicationFiles.find { it.type == AuroraConfigFileType.APP && !it.override }!!

    val overrideFiles: Map<String, String>
        get() = applicationFiles.filter { it.override }.associate { it.name to it.contents }
}
