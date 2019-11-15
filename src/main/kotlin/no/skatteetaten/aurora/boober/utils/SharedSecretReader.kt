package no.skatteetaten.aurora.boober.utils

import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

/**
 * Component for reading the shared secret used for authentication. You may specify the shared secret directly using
 * the aurora.token.value property, or specify a file containing the secret with the aurora.token.location property.
 */
@Component
class SharedSecretReader(
    @Value("\${aurora.token.location:}") val secretLocation: String?,
    @Value("\${aurora.token.value:}") val secretValue: String?
) {

    init {
        if (listOf(secretLocation, secretValue).all { it.isNullOrEmpty() }) {
            throw IllegalArgumentException("Either aurora.token.location or aurora.token.value must be specified")
        }
    }

    // use byLazy on this one?
    val secret: String by lazy {
        secretValue.takeIf { it.isNullOrBlank() }?.let {
            val secretFile = File(secretLocation).absoluteFile
            try {
                logger.debug("Reading token from file {}", secretFile.absolutePath)
                String(Files.readAllBytes(Paths.get(secretLocation)), Charsets.UTF_8)
            } catch (e: IOException) {
                throw IllegalStateException("Unable to read shared secret from specified location [${secretFile.absolutePath}]")
            }
        } ?: secretValue!!
    }
}
