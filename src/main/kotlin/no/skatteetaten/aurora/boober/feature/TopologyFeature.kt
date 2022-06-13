package no.skatteetaten.aurora.boober.feature

import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import org.springframework.stereotype.Service

private const val FEATURE_FIELD: String = "topology"

@Service
class TopologyFeature : Feature {
    val allowedFileTypes = setOf(
        AuroraConfigFileType.BASE,
        AuroraConfigFileType.BASE_OVERRIDE,
        AuroraConfigFileType.APP,
        AuroraConfigFileType.APP_OVERRIDE
    )

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(name = "$FEATURE_FIELD/partOf", allowedFilesTypes = allowedFileTypes),
            AuroraConfigFieldHandler(name = "$FEATURE_FIELD/runtime", allowedFilesTypes = allowedFileTypes),
            AuroraConfigFieldHandler(name = "$FEATURE_FIELD/connectsTo", allowedFilesTypes = allowedFileTypes)
        )
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, context: FeatureContext) {
        val group: String? = adc.getOrNull("$FEATURE_FIELD/partOf")
        val runtime: String? = adc.getOrNull("$FEATURE_FIELD/runtime")
        val connectsTo: List<String> = adc.getDelimitedStringOrArrayAsSet("$FEATURE_FIELD/connectsTo")
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
                        "\"$it\""
                    }.toString()
                }
            }
    }
}
