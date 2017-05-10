package no.skatteetaten.aurora.boober.utils

import java.util.*


inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {

    var currentThrowable: Throwable? = null
    try {
        return block(this)
    } catch (throwable: Throwable) {
        currentThrowable = throwable
        throw throwable
    } finally {
        if (currentThrowable != null) {
            try {
                this.close()
            } catch (throwable: Throwable) {
                currentThrowable.addSuppressed(throwable)
            }
        } else {
            this.close()
        }
    }
}

inline fun String.base64encode(): String =
        Base64.getEncoder().encodeToString(this.toByteArray(java.nio.charset.StandardCharsets.UTF_8))