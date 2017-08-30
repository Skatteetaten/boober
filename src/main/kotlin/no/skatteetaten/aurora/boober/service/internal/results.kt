package no.skatteetaten.aurora.boober.service.internal

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraApplicationConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import org.eclipse.jgit.lib.PersonIdent
import org.springframework.http.ResponseEntity

data class Result<out V, out E>(val value: V? = null, val error: E? = null)


data class ApplicationCommand @JvmOverloads constructor(
        val deployId: String,
        val auroraDc: AuroraApplicationConfig,
        val commands: List<OpenshiftCommand>,
        val tagCommand: TagCommand? = null)

data class TagCommand @JvmOverloads constructor(
        val name: String,
        val from: String,
        val to: String,
        val fromRegistry: String,
        val toRegistry: String = fromRegistry)

data class ApplicationResult @JvmOverloads constructor(
        val deployId: String,
        val auroraDc: AuroraApplicationConfig,
        val openShiftResponses: List<OpenShiftResponse> = listOf(),
        val tagCommandResponse: ResponseEntity<JsonNode>? = null) {
    val tag: String = "${auroraDc.namespace}.${auroraDc.name}/${deployId}"
}

fun <T : Any> List<Result<T?, Error?>>.orElseThrow(block: (List<Error>) -> Exception): List<T> {
    this.mapNotNull { it.error }
            .takeIf { it.isNotEmpty() }
            ?.let { throw block(it) }

    return this.mapNotNull { it.value }
}


data class DeployHistory(val ident: PersonIdent, val result: JsonNode)