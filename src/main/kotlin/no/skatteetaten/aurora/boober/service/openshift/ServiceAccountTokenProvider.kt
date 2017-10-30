package no.skatteetaten.aurora.boober.service.openshift;

import com.google.common.base.Supplier
import com.google.common.base.Suppliers
import com.google.common.io.Files
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException

/**
 * Loader for the Application Token that will be used when loading resources from Openshift that does not require
 * an authenticated user.
 *
 * @param tokenLocation the location on the file system for the file that contains the token
 * @param tokenOverride an optional override of the token that will be used instead of the one on the file system
 *                      - useful for development and testing.
 */
@Component
class ServiceAccountTokenProvider(
        @Value("\${boober.openshift.tokenLocation}") val tokenLocation: String,
        @Value("\${boober.openshift.token:}") val tokenOverride: String
) : TokenProvider {

    private val logger: Logger = LoggerFactory.getLogger(ServiceAccountTokenProvider::class.java)

    private val tokenSupplier: Supplier<String> = Suppliers.memoize({ readToken() })

    /**
     * Get the Application Token by using the specified tokenOverride if it is set, or else reads the token from the
     * specified file system path. Any value used will be cached forever, so potential changes on the file system will
     * not be picked up.
     *
     * @return
     */
    override fun getToken() =  tokenSupplier.get()

    fun readToken(): String {
        return if (tokenOverride.isBlank()) {
            readTokenFromFile()
        } else {
            tokenOverride
        }
    }

    fun readTokenFromFile(): String {
        logger.info("Reading application token from tokenLocation={}", tokenLocation)
        try {
            val token: String = Files.toString(File(tokenLocation), Charsets.UTF_8).trimEnd()
            logger.trace("Read token with length={}, firstLetter={}, lastLetter={}", token.length,
                    token[0], token[token.length - 1])
            return token
        } catch (e: IOException) {
            throw IllegalStateException("tokenLocation=$tokenLocation could not be read", e)
        }
    }
}
