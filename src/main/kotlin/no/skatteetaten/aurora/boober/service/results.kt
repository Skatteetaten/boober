package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import java.time.Instant
import java.util.UUID

data class AuroraDeployResult @JvmOverloads constructor(
        val command: ApplicationDeploymentCommand? = null,
    //TODO: This cannot be here
        val auroraDeploymentSpecInternal: AuroraDeploymentSpec? = null,
        val deployId: String = UUID.randomUUID().toString().substring(0, 7),
        val openShiftResponses: List<OpenShiftResponse> = listOf(),
        val success: Boolean = true,
        val ignored: Boolean = false,
        val reason: String? = null,
        val tagResponse: TagResult? = null,
        val projectExist: Boolean = false,
        val bitbucketStoreResult: String? = null
)

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