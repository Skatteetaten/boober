package no.skatteetaten.aurora.boober.model

data class ApplicationRef(val namespace: String, val name: String)

data class ApplicationDeploymentRef(val environment: String, val application: String) {
    override fun toString() = "$environment/$application"

    companion object {
        fun fromString(string: String): ApplicationDeploymentRef {
            val split = string.split("/")
            if (split.size != 2) throw IllegalArgumentException("Unsupported adr format $string")
            return ApplicationDeploymentRef(split[0], split[1])
        }

        @JvmStatic
        fun adr(environment: String, application: String): ApplicationDeploymentRef {
            return ApplicationDeploymentRef(environment, application)
        }
    }
}
