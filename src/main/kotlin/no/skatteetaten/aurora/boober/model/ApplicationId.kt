package no.skatteetaten.aurora.boober.model

data class ApplicationId(
        val environmentName: String,
        val applicationName: String
) {
    override fun toString(): String {
        return "$environmentName-$applicationName"
    }
}