package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.mapper.AuroraConfigField
import java.util.*

data class Error(
        val application: String,
        val environment: String,
        val messages: List<ValidationError> = listOf()
)

abstract class ServiceException(message: String?, cause: Throwable?) : RuntimeException(message, cause) {
    constructor(message: String) : this(message, null)
}

class OpenShiftException(messages: String?, cause: Throwable?) : ServiceException(messages, cause)

class GitException(messages: String?, cause: Throwable?) : ServiceException(messages, cause)

class ApplicationConfigException(
        messages: String,
        cause: Throwable? = null,
        val errors: List<ValidationError> = listOf()
) : ServiceException(messages, cause)


class AuroraConfigException(
        message: String,
        val errors: List<Error> = listOf()
) : ServiceException(message)

data class ValidationError(val message: String, val field: AuroraConfigField? = null)

class AuroraVersioningException(message: String, val errors: List<VersioningError>) : ServiceException(message)

data class VersioningError(val fileName: String, val name: String, val date: Date)


