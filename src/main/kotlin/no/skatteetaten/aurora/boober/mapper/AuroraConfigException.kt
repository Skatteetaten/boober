package no.skatteetaten.aurora.boober.mapper

import no.skatteetaten.aurora.boober.model.ConfigFieldError

class AuroraConfigException(
        message: String,
        val errors: List<ConfigFieldError> = listOf()
) : RuntimeException(message) {
    override val message: String?
        get() {
            val message = super.message
            val errorMessages = errors.map { it.message }.joinToString(", ")
            return "$message. $errorMessages."
        }
}
