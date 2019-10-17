package no.skatteetaten.aurora.boober.model

import no.skatteetaten.aurora.boober.controller.security.User

data class AuroraDeployCommand(
    val headerResources: Set<AuroraResource>,
    val resources: Set<AuroraResource>,
    val context: AuroraDeploymentContext,
    val deployId: String,
    val shouldDeploy: Boolean = true,
    val user: User
)
