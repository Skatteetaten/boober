package no.skatteetaten.aurora.boober.model;


class DeployBundle(
        var auroraConfig: AuroraConfig,
        val overrideFiles: List<AuroraConfigFile> = listOf()
)
