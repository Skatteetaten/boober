package no.skatteetaten.aurora.boober.service

abstract class ServiceException(message: String?, cause: Throwable?) : RuntimeException(message, cause)

class OpenShiftException(messages: String?, cause: Throwable?) : ServiceException(messages, cause)

class ValidationException(
        messages: String?,
        cause: Throwable? = null,
        val errors: Map<String, String>? = mapOf()
) : ServiceException(messages, cause)