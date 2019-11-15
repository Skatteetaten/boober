package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.ApplicationError
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.ConfigFieldErrorDetail
import no.skatteetaten.aurora.boober.model.ErrorDetail

abstract class ServiceException(message: String?, cause: Throwable?) : RuntimeException(message, cause) {
    constructor(message: String) : this(message, null)
}

class OpenShiftException(messages: String?, cause: Throwable? = null) : ServiceException(messages, cause)

class AuroraDeploymentSpecValidationException(message: String, cause: Throwable? = null) :
    ServiceException(message, cause)

class UnauthorizedAccessException(message: String) : ServiceException(message)

class ExceptionList(val exceptions: List<Exception>) : RuntimeException()

data class ContextErrors(val command: AuroraContextCommand, val errors: List<Throwable>)

class MultiApplicationValidationException(
    val errors: List<ContextErrors> = listOf()
) : ServiceException("An error occurred for one or more applications") {

    fun toValidationErrors(): List<ApplicationError> {
        return this.errors.flatMap {
            it.errors.map { t ->
                ApplicationError(
                    it.command.applicationDeploymentRef.application, it.command.applicationDeploymentRef.environment,
                    when (t) {
                        is AuroraConfigException -> t.errors
                        is IllegalArgumentException -> listOf(ConfigFieldErrorDetail.illegal(t.message ?: ""))
                        else -> listOf(ErrorDetail(message = t.message ?: ""))
                    }
                )
            }
        }
    }
}

class ProvisioningException(message: String, cause: Throwable? = null) :
    ServiceException(message, cause)

class AuroraConfigServiceException(message: String, cause: Throwable? = null) : ServiceException(message, cause)

class GitReferenceException(message: String, cause: Throwable? = null) : ServiceException(message, cause)

class DeployLogServiceException(message: String, cause: Throwable? = null) : ServiceException(message, cause)
