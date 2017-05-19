package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraObjectsConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse

data class Result<out V, out E>(val value: V? = null, val error: E? = null)

data class ApplicationResult(
        val applicationId: ApplicationId,
        val auroraDc: AuroraObjectsConfig,
        val openShiftResponses: List<OpenShiftResponse> = listOf()
)

fun <T : Any> List<Result<T?, Error?>>.orElseThrow(block: (List<Error>) -> Exception): List<T> {
    this.mapNotNull { it.error }
            .takeIf { it.isNotEmpty() }
            ?.let { throw block(it) }

    return this.mapNotNull { it.value }
}
