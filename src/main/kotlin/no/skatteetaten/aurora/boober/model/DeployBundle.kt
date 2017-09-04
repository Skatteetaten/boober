package no.skatteetaten.aurora.boober.model;

import org.eclipse.jgit.api.Git

class DeployBundle(
        val repo: Git,
        var auroraConfig: AuroraConfig,
        val vaults: Map<String, AuroraSecretVault>,
        val overrideFiles: List<AuroraConfigFile> = listOf()
)
