package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.ConfigFieldError
import no.skatteetaten.aurora.boober.model.Error
import no.skatteetaten.aurora.boober.model.VersioningError

abstract class ServiceException(message: String?, cause: Throwable?) : RuntimeException(message, cause) {
    constructor(message: String) : this(message, null)
}

class OpenShiftException(messages: String?, cause: Throwable?) : ServiceException(messages, cause)

class GitException(messages: String?, cause: Throwable?) : ServiceException(messages, cause)

class AuroraDeploymentSpecValidationException(message: String) : ServiceException(message)

class ValidationException(
        message: String,
        val errors: List<Error> = listOf()
) : ServiceException(message)

class AuroraConfigException(
        message: String,
        val errors: List<ConfigFieldError> = listOf()
) : ServiceException(message) {
    override val message: String?
        get() {
            val message = super.message
            val errorMessages = errors.map { it.message }.joinToString(", ")
            return "$message. $errorMessages."
        }
}

class AuroraVersioningException(message: String, val errors: List<VersioningError>) : ServiceException(message)

class ProvisioningException(message: String, cause: Throwable? = null) : ServiceException(message, cause)


