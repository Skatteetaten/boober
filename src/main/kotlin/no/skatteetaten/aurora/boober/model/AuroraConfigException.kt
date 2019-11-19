package no.skatteetaten.aurora.boober.model

class AuroraConfigException(
    message: String,
    val errors: List<ConfigFieldErrorDetail> = listOf()
) : RuntimeException(message) {
    override val message: String?
        get() {
            val message = super.message
            val errorMessages = errors.map { it.message }.joinToString(", ")
            return "$message. $errorMessages."
        }
}
