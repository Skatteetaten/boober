package no.skatteetaten.aurora.boober.model

data class ApplicationDeploymentRef(val environment: String, val application: String) {
    override fun toString() = "$environment/$application"

    companion object {
        fun fromString(string: String): ApplicationDeploymentRef {
            val split = string.split("/")
            if (split.size != 2) throw IllegalArgumentException("Unsupported aid format $string")
            return ApplicationDeploymentRef(split[0], split[1])
        }

        @JvmStatic
        fun aid(environment: String, application: String): ApplicationDeploymentRef {
            return ApplicationDeploymentRef(environment, application)
        }
    }
}
