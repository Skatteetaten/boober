package no.skatteetaten.aurora.boober.model

data class ApplicationId(val environment: String, val application: String) {

    override fun toString() = "$environment/$application"

    companion object {
        fun fromString(string: String): ApplicationId {
            val split = string.split("/")
            if (split.size != 2) throw IllegalArgumentException("Unsupported aid format $string")
            return ApplicationId(split[0], split[1])
        }

        @JvmStatic
        fun aid(environment: String, application: String): ApplicationId {
            return ApplicationId(environment, application)
        }
    }
}
