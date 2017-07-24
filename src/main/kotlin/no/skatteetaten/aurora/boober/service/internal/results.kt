package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse

data class Result<out V, out E>(val value: V? = null, val error: E? = null)

data class ApplicationResult @JvmOverloads constructor(
        val deployCommand: DeployCommand,
        val auroraDc: AuroraDeploymentConfig,
        val openShiftResponses: List<OpenShiftResponse> = listOf(),
        val deletedObjectUrls: List<String> = listOf(),
        val deployId:String
)              {
    val tag:String = "$deployCommand.applicationId-$deployId"
}

fun <T : Any> List<Result<T?, Error?>>.orElseThrow(block: (List<Error>) -> Exception): List<T> {
    this.mapNotNull { it.error }
            .takeIf { it.isNotEmpty() }
            ?.let { throw block(it) }

    return this.mapNotNull { it.value }
}
