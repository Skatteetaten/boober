package no.skatteetaten.aurora.boober.model;


class DeployBundle(
        var auroraConfig: AuroraConfig,
        val vaults: Map<String, AuroraSecretVault>,
        val overrideFiles: List<AuroraConfigFile> = listOf()
)
