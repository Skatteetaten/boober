package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import java.time.Instant
import java.util.UUID

data class AuroraDeployResult @JvmOverloads constructor(
    val auroraDeploymentSpecInternal: AuroraDeploymentSpecInternal? = null,
    val deployId: String = UUID.randomUUID().toString().substring(0, 7),
    val openShiftResponses: List<OpenShiftResponse> = listOf(),
    val success: Boolean = true,
    val ignored: Boolean = false,
    val reason: String? = null,
    val tagResponse: TagResult? = null,
    val projectExist: Boolean = false
) {
    val tag: String = "${auroraDeploymentSpecInternal?.cluster}.${auroraDeploymentSpecInternal?.environment?.namespace}.${auroraDeploymentSpecInternal?.name}/$deployId"
}

data class DeployHistory(val deployer: Deployer, val time: Instant, val result: JsonNode)

data class Deployer(val name: String, val email: String)