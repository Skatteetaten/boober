package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.resources
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.QuantityBuilder
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

fun AuroraDeploymentSpec.quantity(resource: String, classifier: String): Pair<String, Quantity> = resource to QuantityBuilder().withAmount(this["resources/$resource/$classifier"]).build()

@Service
class DeploymentConfigFeature() : Feature {
    override fun handlers(header: AuroraDeploymentSpec, adr: ApplicationDeploymentRef, files: List<AuroraConfigFile>, auroraConfig: AuroraConfig): Set<AuroraConfigFieldHandler> {
        return setOf(
                AuroraConfigFieldHandler("resources/cpu/min", defaultValue = "10m"),
                AuroraConfigFieldHandler("resources/cpu/max", defaultValue = "2000m"),
                AuroraConfigFieldHandler("resources/memory/min", defaultValue = "128Mi"),
                AuroraConfigFieldHandler("resources/memory/max", defaultValue = "512Mi")
        )
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>): Set<AuroraResource> {
        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
                dc.spec.template.spec.containers.forEach { container ->
                    container.resources {
                        requests = mapOf(adc.quantity("cpu", "min"), adc.quantity("memory", "min"))
                        limits = mapOf(adc.quantity("cpu", "max"), adc.quantity("memory", "max"))
                    }
                }
            }
        }
        return resources
    }
}