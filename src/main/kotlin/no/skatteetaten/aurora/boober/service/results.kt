package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import org.eclipse.jgit.lib.PersonIdent

data class AuroraEnvironmentResult(
        val openShiftResponses: List<OpenShiftResponse> = listOf(),
        val newNamespace: Boolean
)


data class AuroraDeployResult @JvmOverloads constructor(
        val deployId: String,
        val auroraDeploymentSpec: AuroraDeploymentSpec,
        val openShiftResponses: List<OpenShiftResponse> = listOf(),
        val success: Boolean = true,
        val tagResponse: TagResult? = null) {
    val tag: String = "${auroraDeploymentSpec.cluster}.${auroraDeploymentSpec.environment.namespace}.${auroraDeploymentSpec.name}/${deployId}"
}

data class DeployHistory(val ident: PersonIdent, val result: JsonNode)