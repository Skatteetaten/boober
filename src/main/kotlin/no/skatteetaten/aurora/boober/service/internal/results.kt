package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse

data class ApplicationResult(
        val applicationId: ApplicationId,
        val auroraDc: AuroraDeploymentConfig,
        val openShiftResponses: List<OpenShiftResponse> = listOf()
)
data class Result<out V, out E>(val value: V? = null, val error: E? = null)

fun <T : Any> List<Result<T?, Error?>>.orElseThrow(block: (List<Error>) -> Exception): List<T> {
    this.mapNotNull { it.error }
            .takeIf { it.isNotEmpty() }
            ?.let { throw block(it) }

    return this.mapNotNull { it.value }
}
