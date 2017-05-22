package no.skatteetaten.aurora.boober.service.internal

import no.skatteetaten.aurora.boober.mapper.AuroraConfigField
import no.skatteetaten.aurora.boober.model.ApplicationId

data class Error(
        val applicationId: ApplicationId,
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