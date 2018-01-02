package no.skatteetaten.aurora.boober.model;


class DeployBundle(
        var auroraConfig: AuroraConfig,
        val vaults: Map<String, EncryptedFileVault>,
        val overrideFiles: List<AuroraConfigFile> = listOf()
)
