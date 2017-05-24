package no.skatteetaten.aurora.boober.model

data class DeployCommand @JvmOverloads constructor(
        val environmentName: String,
        val applicationName: String,
        private val overrideFiles: List<AuroraConfigFile> = listOf()
) {
    override fun toString(): String {
        return "$environmentName-$applicationName"
    }

    val requiredFilesForApplication = setOf(
            "about.json",
            "$applicationName.json",
            "$environmentName/about.json",
            "$environmentName/$applicationName.json")

    val overrides = requiredFilesForApplication.mapNotNull { fileName -> overrideFiles.find { it.name == fileName } }
}