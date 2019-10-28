package no.skatteetaten.aurora.boober.model

data class ApplicationRef(val namespace: String, val name: String)

data class ApplicationDeploymentRef(val environment: String, val application: String) {
    // TODO: do we really need this here?
    override fun toString() = "$environment/$application"
}

fun String.toAdr(): ApplicationDeploymentRef {
    val split = this.split("/")
    require(split.size == 2) { "Unsupported adr format $this" }
    return ApplicationDeploymentRef(split[0], split[1])
}
