package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.*

abstract class ServiceException(message: String?, cause: Throwable?) : RuntimeException(message, cause) {
    constructor(message: String) : this(message, null)
}

class OpenShiftException(messages: String?, cause: Throwable?) : ServiceException(messages, cause)

class GitException(messages: String?, cause: Throwable?) : ServiceException(messages, cause)

class AuroraDeploymentSpecValidationException(message: String) : ServiceException(message)

data class ExceptionWrapper(val aid: ApplicationId, val throwable: Throwable)

class MultiApplicationValidationException(
        val errors: List<ExceptionWrapper> = listOf()
) : ServiceException("An error occurred for one or more applications") {

    fun toValidationErrors(): List<ValidationError> {
        return this.errors.map {
            val t = it.throwable
            ValidationError(it.aid.application, it.aid.environment,
                    when (t) {
                        is AuroraConfigException -> t.errors
                        is IllegalArgumentException -> listOf(ConfigFieldError.illegal(t.message!!))
                        else -> listOf(GenericError(t.message!!))
                    })
        }
    }
}

fun List<Pair<AuroraDeploymentSpec?, ExceptionWrapper?>>.onErrorThrow(block: (List<ExceptionWrapper>) -> Exception): List<AuroraDeploymentSpec> {
    this.mapNotNull { it.second }
            .takeIf { it.isNotEmpty() }
            ?.let { throw block(it) }

    return this.mapNotNull { it.first }
}

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


