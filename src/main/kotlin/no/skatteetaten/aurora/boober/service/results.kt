package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.mapper.AuroraDeployCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import java.time.Instant
import java.util.UUID


data class AuroraEnvironmentResult @JvmOverloads constructor(
        val openShiftResponses: List<OpenShiftResponse> = listOf(),
        val success: Boolean = true,
        val reason: String? = null,
        val projectExist: Boolean = false)

data class AuroraDeployResult @JvmOverloads constructor(
        val openShiftResponses: List<OpenShiftResponse> = listOf(),
        val success: Boolean = true,
        val reason: String? = null,
        val projectExist: Boolean = false,
        val tagResponse: TagResult? = null,
        val bitbucketStoreResult: String? = null,
        val deployCommand: AuroraDeployCommand
//what if there is an error will this command always be there?
) {
    val auroraDeploymentSpecInternal: AuroraDeploymentSpec get() = deployCommand.context.spec

    val command: ApplicationDeploymentCommand
        get() = ApplicationDeploymentCommand(
                deployCommand.context.cmd.overrideFiles,
                deployCommand.context.cmd.applicationDeploymentRef,
                deployCommand.context.cmd.auroraConfigRef)

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
)

data class DeployHistoryEntryResult(val openshift: List<OpenShiftResponse>, val tagResult: TagResult?)

data class Deployer(val name: String, val email: String)