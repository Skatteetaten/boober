package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.ApplicationError
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeployCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.ConfigFieldErrorDetail
import no.skatteetaten.aurora.boober.model.ErrorDetail
import no.skatteetaten.aurora.boober.model.ErrorType.SKIPPED

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
    val errors: List<ContextErrors> = listOf(),
    val errorMessage: String = "An error occurred for one or more applications"
) : ServiceException(errorMessage) {

    fun toValidationErrors(): List<ApplicationError> {
        return this.errors.flatMap {
            it.applicationErrorMapper()
        }
    }
}

class MultiApplicationValidationResultException(
    val valid: List<AuroraDeploymentContext> = listOf(),
    val invalid: List<Pair<AuroraDeploymentContext?, ContextErrors?>> = listOf(),
    val errorMessage: String = "An error occurred for one or more applications"
) : ServiceException(errorMessage) {
    fun toValidationErrors(): List<ApplicationError> = listOf(
        invalid.map {
            it.second?.applicationErrorMapper() ?: listOf(
                ApplicationError(
                    it.first!!.cmd.applicationDeploymentRef.application,
                    it.first!!.cmd.applicationDeploymentRef.environment,
                    listOf(
                        ErrorDetail(
                            type = SKIPPED,
                            message = "Skipped due to validation errors in multi application deployment"
                        )
                    )
                )
            )
        }.flatten(),
        valid.map {
            ApplicationError(
                it.cmd.applicationDeploymentRef.application,
                it.cmd.applicationDeploymentRef.environment,
                listOf(
                    ErrorDetail(
                        type = SKIPPED,
                        message = "Skipped due to validation errors in multi application deployment"
                    )
                )
            )
        }
    ).flatten()
}

class MultiApplicationDeployValidationResultException(
    val valid: List<AuroraDeployCommand> = listOf(),
    val invalid: List<ContextErrors> = listOf(),
    val errorMessage: String = "An error occurred for one or more applications"
) : ServiceException(errorMessage) {
    fun toValidationErrors(): List<ApplicationError> = listOf(
        invalid.map {
            it.applicationErrorMapper()
        }.flatten(),
        valid.map {
            ApplicationError(
                it.context.cmd.applicationDeploymentRef.application,
                it.context.cmd.applicationDeploymentRef.environment,
                listOf(
                    ErrorDetail(
                        type = SKIPPED,
                        message = "Deploy skipped due to validation errors in multi application deployment commands"
                    )
                )
            )
        }
    ).flatten()
}

fun ContextErrors.applicationErrorMapper(): List<ApplicationError> = errors.map { t ->
    ApplicationError(
        this.command.applicationDeploymentRef.application,
        this.command.applicationDeploymentRef.environment,
        when (t) {
            is AuroraConfigException -> t.errors
            is IllegalArgumentException -> listOf(ConfigFieldErrorDetail.illegal(t.message ?: ""))
            else -> listOf(ErrorDetail(message = t.message ?: ""))
        }
    )
}

open class ProvisioningException(message: String, cause: Throwable? = null) :
    ServiceException(message, cause)

class AuroraConfigServiceException(message: String, cause: Throwable? = null) : ServiceException(message, cause)
class AuroraVaultServiceException(message: String, cause: Throwable? = null) : ServiceException(message, cause)

class GitReferenceException(message: String, cause: Throwable? = null) : ServiceException(message, cause)

class DeployLogServiceException(message: String, cause: Throwable? = null) : ServiceException(message, cause)

class NotificationServiceException(message: String, cause: Throwable? = null) : ServiceException(message, cause)
