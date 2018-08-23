package no.skatteetaten.aurora.boober.controller.internal

import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile

data class ApplyPayload(
    val applicationDeploymentRefs: List<ApplicationDeploymentRef>,
    val overrides: Map<String, String> = mapOf(),
    val deploy: Boolean = true
) {
    fun overridesToAuroraConfigFiles(): List<AuroraConfigFile> {
        return overrides.map { AuroraConfigFile(it.key, it.value, true) }
    }
}