package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.mapper.AuroraConfigException
import no.skatteetaten.aurora.boober.model.ApplicationError
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ConfigFieldErrorDetail
import no.skatteetaten.aurora.boober.model.ErrorDetail

abstract class ServiceException(message: String?, cause: Throwable?) : RuntimeException(message, cause) {
    constructor(message: String) : this(message, null)
}

class OpenShiftException(messages: String?, cause: Throwable? = null) : ServiceException(messages, cause)

class AuroraDeploymentSpecValidationException(message: String) : ServiceException(message)

class UnauthorizedAccessException(message: String) : ServiceException(message)

data class ExceptionWrapper(val aid: ApplicationId, val throwable: Throwable)

class MultiApplicationValidationException(
    val errors: List<ExceptionWrapper> = listOf()
) : ServiceException("An error occurred for one or more applications") {

    fun toValidationErrors(): List<ApplicationError> {
        return this.errors.map {
            val t = it.throwable
            ApplicationError(
                it.aid.application, it.aid.environment,
                when (t) {
                    is AuroraConfigException -> t.errors
                    is IllegalArgumentException -> listOf(ConfigFieldErrorDetail.illegal(t.message!!))
                    else -> listOf(ErrorDetail(message = t.message!!))
                }
            )
        }
    }
}

fun List<Pair<AuroraDeploymentSpec?, ExceptionWrapper?>>.onErrorThrow(block: (List<ExceptionWrapper>) -> Exception): List<AuroraDeploymentSpec> {
    this.mapNotNull { it.second }
        .takeIf { it.isNotEmpty() }
        ?.let { throw block(it) }

    return this.mapNotNull { it.first }
}

class ProvisioningException @JvmOverloads constructor(message: String, cause: Throwable? = null) :
    ServiceException(message, cause)

class AuroraConfigServiceException(message: String, cause: Throwable? = null) : ServiceException(message, cause)

class GitReferenceException(message: String, cause: Throwable? = null) : ServiceException(message, cause)
