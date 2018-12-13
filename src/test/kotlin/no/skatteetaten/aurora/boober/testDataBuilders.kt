package no.skatteetaten.aurora.boober

import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.DeployHistoryEntry
import no.skatteetaten.aurora.boober.service.DeployHistoryEntryResult
import no.skatteetaten.aurora.boober.service.Deployer
import java.time.Instant

data class DeployHistoryEntryBuilder(val deployId: String = "123") {
    fun build() = DeployHistoryEntry(
        deployId = deployId,
        deployer = Deployer("name", "email"),
        time = Instant.now(),
        success = true,
        reason = "",
        projectExist = true,
        command = ApplicationDeploymentCommand(
            emptyMap(),
            ApplicationDeploymentRef("env", "app"),
            AuroraConfigRef("name", "refName")
        ),
        deploymentSpec = emptyMap(),
        result = DeployHistoryEntryResult(emptyList(), null)
    )
}