package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeployCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import java.time.Instant
import com.fasterxml.jackson.module.kotlin.readValue

data class AuroraEnvironmentResult(
    val openShiftResponses: List<OpenShiftResponse> = listOf(),
    val success: Boolean = true,
    val reason: String? = null,
    val projectExist: Boolean = false
)

// TODO: Strukturen her kan kanskje være bedre, EnvironmentResult f.eks? Idag blir alle responsene for environment result kopiert inn overalt.
// TODO: assert on deploy command
data class AuroraDeployResult(
    val openShiftResponses: List<OpenShiftResponse> = listOf(),
    val success: Boolean = true,
    val reason: String? = null,
    val projectExist: Boolean = false,
    val tagResponse: TagResult? = null,
    val bitbucketStoreResult: String? = null,
    val deployCommand: AuroraDeployCommand,
    val warnings: List<String> = emptyList()
// what if there is an error will this command always be there?
) {
    val auroraDeploymentSpecInternal: AuroraDeploymentSpec get() = deployCommand.context.spec

    val command: ApplicationDeploymentCommand
        get() = ApplicationDeploymentCommand(
            deployCommand.context.cmd.overrideFiles,
            deployCommand.context.cmd.applicationDeploymentRef,
            deployCommand.context.cmd.auroraConfigRef
        )

    val deployId: String get() = deployCommand.deployId
}

data class DeployHistoryEntry(
    val version: String = "v2",
    val deployId: String,
    val deployer: Deployer,
    val time: Instant,
    val success: Boolean,
    val projectExist: Boolean,
    val reason: String,
    val command: ApplicationDeploymentCommand,
    val deploymentSpec: Map<String, Any>,
    val result: DeployHistoryEntryResult
) {
    companion object {
        fun fromString(content: String): DeployHistoryEntry = objectMapperWithTime.readValue(content)
    }
}

data class DeployHistoryEntryResult(val openshift: List<OpenShiftResponse>, val tagResult: TagResult?)

data class Deployer(val name: String, val email: String)
