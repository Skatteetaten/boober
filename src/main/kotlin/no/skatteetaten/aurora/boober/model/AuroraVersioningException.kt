package no.skatteetaten.aurora.boober.model

data class VersioningError(val auroraConfigName: String, val fileName: String, val currentHash: String, val providedHash: String)

class AuroraVersioningException(auroraConfig: AuroraConfig, currentFile: AuroraConfigFile, previousVersion: String) :
    PreconditionFailureException("The provided version of the current file ($previousVersion) in AuroraConfig ${auroraConfig.name} is not correct (${currentFile.version})") {
    val errors: List<VersioningError>

    init {
        errors = listOf(VersioningError(auroraConfig.name, currentFile.name, currentFile.version, previousVersion))
    }
}
