package no.skatteetaten.aurora.boober.utils

import org.springframework.http.ResponseEntity
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.service.EmptyBodyException

private val logger = KotlinLogging.logger {}

fun <T> ResponseEntity<T>.getBodyOrThrow(serviceName: String) =
    this.body ?: throw EmptyBodyException("Fatal error happened. Received empty body from $serviceName").also {
        logger.error(it) { "Null body happened in caller method=${it.stackTrace[2]} statusCode=${this.statusCode}" }
    }
