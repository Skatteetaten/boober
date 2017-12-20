package no.skatteetaten.aurora.boober.model;


class DeployBundle(
        var auroraConfig: AuroraConfig,
        val vaults: Map<String, Vault>,
        val overrideFiles: List<AuroraConfigFile> = listOf()
)
