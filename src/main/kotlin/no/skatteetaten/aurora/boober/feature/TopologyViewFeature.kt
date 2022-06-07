package no.skatteetaten.aurora.boober.feature

import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import org.springframework.stereotype.Service

@Service
class TopologyViewFeature : Feature {

    val topology = "topologyView"
    val allowedFileTypes = setOf(
        AuroraConfigFileType.BASE,
        AuroraConfigFileType.BASE_OVERRIDE,
        AuroraConfigFileType.APP,
        AuroraConfigFileType.APP_OVERRIDE
    )

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(name = "$topology/group", allowedFilesTypes = allowedFileTypes),
            AuroraConfigFieldHandler(name = "$topology/runtime", allowedFilesTypes = allowedFileTypes),
            AuroraConfigFieldHandler(name = "$topology/connectsTo", allowedFilesTypes = allowedFileTypes)
        )
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, context: FeatureContext) {
        val group: String? = adc.getOrNull("$topology/group")
        val runtime: String? = adc.getOrNull("$topology/runtime")
        val connectsTo: List<String> = adc.getDelimitedStringOrArrayAsSet("$topology/connectsTo")
            .filter { it.isNotBlank() }

        resources
            .filter { it.resource.kind == "DeploymentConfig" }
            .forEach { auroraResource ->
                val dc: DeploymentConfig = auroraResource.resource as DeploymentConfig

                group?.let { dc.metadata.labels["app.kubernetes.io/part-of"] = it }
                runtime?.let { dc.metadata.labels["app.openshift.io/runtime"] = it }

                if (connectsTo.isNotEmpty()) {
                    if (dc.metadata.annotations == null) {
                        dc.metadata.annotations = mutableMapOf()
                    }
                    dc.metadata.annotations["app.openshift.io/connects-to"] = connectsTo.map {
                        """{"apiVersion":"apps.openshift.io/v1","kind":"DeploymentConfig","name":"$it"}"""
                    }.toString()
                }
            }
    }
}
