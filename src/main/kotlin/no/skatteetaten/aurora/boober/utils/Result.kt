package no.skatteetaten.aurora.boober.utils

import no.skatteetaten.aurora.boober.service.Error

data class Result<out V, out E>(val value: V? = null, val error: E? = null)

fun <T : Any> List<Result<T?, Error?>>.orElseThrow(block: (List<Error>) -> Exception): List<T> {
    this.mapNotNull { it.error }
            .takeIf { it.isNotEmpty() }
            ?.let { throw block(it) }

    return this.mapNotNull { it.value }

}