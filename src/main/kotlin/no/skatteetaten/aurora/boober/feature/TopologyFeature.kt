package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import org.springframework.stereotype.Service

@Service
class TopologyFeature : Feature {
    val topology = "topology"
    val allowedFileTypes = setOf(
        AuroraConfigFileType.BASE,
        AuroraConfigFileType.BASE_OVERRIDE,
        AuroraConfigFileType.APP,
        AuroraConfigFileType.APP_OVERRIDE
    )

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(name = "$topology/partOf", allowedFilesTypes = allowedFileTypes),
            AuroraConfigFieldHandler(name = "$topology/runtime", allowedFilesTypes = allowedFileTypes),
            AuroraConfigFieldHandler(name = "$topology/connectsTo", allowedFilesTypes = allowedFileTypes)
        )
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, context: FeatureContext) {
        val group: String? = adc.getOrNull("$topology/partOf")
        val runtime: String? = adc.getOrNull("$topology/runtime")
        val connectsTo: List<String> = adc.getDelimitedStringOrArrayAsSet("$topology/connectsTo")
            .filter { it.isNotBlank() }

        resources
            .forEach { auroraResource ->
                group?.let { auroraResource.resource.metadata.labels["app.kubernetes.io/part-of"] = it }
                runtime?.let { auroraResource.resource.metadata.labels["app.openshift.io/runtime"] = it }

                if (auroraResource.resource.kind == "DeploymentConfig" && connectsTo.isNotEmpty()) {
                    if (auroraResource.resource.metadata.annotations == null) {
                        auroraResource.resource.metadata.annotations = mutableMapOf()
                    }
                    auroraResource.resource.metadata.annotations["app.openshift.io/connects-to"] = connectsTo.map {
                        it
                    }.toString()
                }
            }
    }
}
