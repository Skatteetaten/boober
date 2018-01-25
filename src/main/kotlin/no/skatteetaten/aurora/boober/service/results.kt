package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import org.eclipse.jgit.lib.PersonIdent
import java.util.*


data class AuroraDeployResult @JvmOverloads constructor(
        val auroraDeploymentSpec: AuroraDeploymentSpec? = null,
        val deployId: String= UUID.randomUUID().toString().substring(0,7),
        val openShiftResponses: List<OpenShiftResponse> = listOf(),
        val success: Boolean = true,
        val ignored:Boolean = false,
        val reason: String? = null,
        val tagResponse: TagResult? = null,
        val projectExist:Boolean=false){
    val tag: String = "${auroraDeploymentSpec?.cluster}.${auroraDeploymentSpec?.environment?.namespace}.${auroraDeploymentSpec?.name}/$deployId"
}



data class DeployHistory(val ident: PersonIdent, val result: JsonNode)