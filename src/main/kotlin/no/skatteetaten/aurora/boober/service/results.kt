package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import java.time.Instant
import java.util.UUID

data class AuroraDeployResult @JvmOverloads constructor(
    val command: ApplicationDeploymentCommand? = null,
    val auroraDeploymentSpecInternal: AuroraDeploymentSpecInternal? = null,
    val deployId: String = UUID.randomUUID().toString().substring(0, 7),
    val openShiftResponses: List<OpenShiftResponse> = listOf(),
    val success: Boolean = true,
    val ignored: Boolean = false,
    val reason: String? = null,
    val tagResponse: TagResult? = null,
    val projectExist: Boolean = false,
    val bitbucketStoreResult: JsonNode? = null
)

data class DeployHistoryEntry(
    val version: String = "v2",
    val command: ApplicationDeploymentCommand,
    val deploymentSpec: Map<String, Any>,
    val deployer: Deployer,
    val time: Instant,
    val deployId: String,
    val success: Boolean,
    val result: DeployHistoryEntryResult,
    val projectExist: Boolean,
    val reason: String
)

data class DeployHistoryEntryResult(val openshift: List<OpenShiftResponse>, val tagResult: TagResult?)

data class Deployer(val name: String, val email: String)