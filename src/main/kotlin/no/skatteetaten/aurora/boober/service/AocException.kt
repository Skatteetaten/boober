package no.skatteetaten.aurora.boober.service

class AocException(messages: String?, cause: Throwable?) : Throwable(messages, cause) {
    constructor(message: String, errors: List<String>) : this(message, null)
    constructor(message: String) : this(message, null)
}