package no.skatteetaten.aurora.boober.service

abstract class ServiceException(message: String?, cause: Throwable?) : RuntimeException(message, cause) {
    constructor(message: String) : this(message, null)
}

class OpenShiftException(messages: String?, cause: Throwable?) : ServiceException(messages, cause)
class GitException(messages: String?, cause: Throwable?) : ServiceException(messages, cause)

class ApplicationConfigException(
        messages: String,
        cause: Throwable? = null,
        val errors: List<String> = listOf()
) : ServiceException(messages, cause)

class AuroraConfigException(
        message: String,
        val errors: List<Error> = listOf()
) : ServiceException(message)