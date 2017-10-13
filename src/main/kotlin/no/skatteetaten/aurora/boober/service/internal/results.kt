package no.skatteetaten.aurora.boober.service.internal

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.TagResult
import no.skatteetaten.aurora.boober.model.Error
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import org.eclipse.jgit.lib.PersonIdent

data class Result<out V, out E>(val value: V? = null, val error: E? = null)

data class AuroraDeployResult @JvmOverloads constructor(
        val deployId: String,
        val auroraDeploymentSpec: AuroraDeploymentSpec,
        val openShiftResponses: List<OpenShiftResponse> = listOf(),
        val tagResponse: TagResult? = null,
        val success: Boolean = true) {
    val tag: String = "${auroraDeploymentSpec.namespace}.${auroraDeploymentSpec.name}/${deployId}"
}

fun <T : Any> List<Result<T?, Error?>>.onErrorThrow(block: (List<Error>) -> Exception): List<T> {
    this.mapNotNull { it.error }
            .takeIf { it.isNotEmpty() }
            ?.let { throw block(it) }

    return this.mapNotNull { it.value }
}


data class DeployHistory(val ident: PersonIdent, val result: JsonNode)