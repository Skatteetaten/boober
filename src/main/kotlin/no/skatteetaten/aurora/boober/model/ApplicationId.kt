package no.skatteetaten.aurora.boober.model

data class ApplicationId(val environment: String, val application: String) {

    override fun toString() = "$environment-$application"

    companion object {
        @JvmStatic
        fun aid(environment: String, application: String): ApplicationId {
            return ApplicationId(environment, application)
        }
    }
}
