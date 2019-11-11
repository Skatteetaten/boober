package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.facade.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType

data class ApplicationRef(val namespace: String, val name: String)

data class ApplicationDeploymentRef(val environment: String, val application: String) {
    override fun toString() = "$environment/$application"
}

fun List<ApplicationRef>.createApplicationDeploymentCommand(operationType: OperationType): List<ApplicationDeploymentCommand> {
    return this.map {
        val url = OpenShiftResourceClient.generateUrl(
            kind = "ApplicationDeployment",
            name = it.name,
            namespace = it.namespace
        )
        val jsonNode = jacksonObjectMapper().readTree("""{"kind":"applicationdeployment"}""")

        ApplicationDeploymentCommand(
            cmd = OpenshiftCommand(operationType, url = url, payload = jsonNode),
            applicationRef = it
        )
    }
}

fun String.toAdr(): ApplicationDeploymentRef {
    val split = this.split("/")
    require(split.size == 2) { "Unsupported adr format $this" }
    return ApplicationDeploymentRef(split[0], split[1])
}
