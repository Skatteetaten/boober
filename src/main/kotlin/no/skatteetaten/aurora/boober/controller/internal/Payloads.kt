package no.skatteetaten.aurora.boober.controller.internal

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile

typealias JsonDataFiles = Map<String, String>


data class ApplyPayload(val applicationIds: List<ApplicationId>,
                        val overrides: JsonDataFiles = mapOf(),
                        val deploy: Boolean = true
) {
    fun overridesToAuroraConfigFiles(): List<AuroraConfigFile> {
        return overrides.map { AuroraConfigFile(it.key, it.value, true) }
    }
}