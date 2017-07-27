package no.skatteetaten.aurora.boober.model

data class DeployCommand @JvmOverloads constructor(
        val applicationId: ApplicationId,
        val overrideFiles: List<AuroraConfigFile> = listOf()
)