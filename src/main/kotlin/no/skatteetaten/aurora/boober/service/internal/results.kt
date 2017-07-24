package no.skatteetaten.aurora.boober.service.internal

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import org.eclipse.jgit.lib.PersonIdent

data class Result<out V, out E>(val value: V? = null, val error: E? = null)


data class ApplicationCommand @JvmOverloads constructor(
        val deployId: String,
        val auroraDc: AuroraDeploymentConfig,
        val commands: List<OpenshiftCommand>,
        val overrides: List<AuroraConfigFile>
        )

data class ApplicationResult @JvmOverloads constructor(
        val command: ApplicationCommand,
        val openShiftResponses: List<OpenShiftResponse> = listOf(),
        val deletedObjectUrls: List<String> = listOf()
)              {
    val tag:String = "${command.auroraDc.namespace}.${command.auroraDc.name}/${command.deployId}"
}

fun <T : Any> List<Result<T?, Error?>>.orElseThrow(block: (List<Error>) -> Exception): List<T> {
    this.mapNotNull { it.error }
            .takeIf { it.isNotEmpty() }
            ?.let { throw block(it) }

    return this.mapNotNull { it.value }
}


data class DeployHistory (val ident: PersonIdent, val result: JsonNode)