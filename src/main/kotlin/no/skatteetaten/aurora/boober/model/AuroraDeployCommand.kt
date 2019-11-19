package no.skatteetaten.aurora.boober.model

data class AuroraDeployCommand(
    val headerResources: Set<AuroraResource>,
    val resources: Set<AuroraResource>,
    val context: AuroraDeploymentContext,
    val deployId: String,
    val shouldDeploy: Boolean = true
)
