package no.skatteetaten.aurora.boober.model

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.allNonSideCarContainers
import java.time.Instant

data class AuroraResource(
    val resource: HasMetadata,
    val header: Boolean = false, // these resources are only created once for each deploy){}){}
    val sources: Set<AuroraResourceSource> = emptySet()

)

data class AuroraResourceSource(
    val feature: Class<out Feature>,
    val comment: String? = "",
    val initial: Boolean = false,
    val time: Instant = Instants.now
)

fun Set<AuroraResource>.addEnvVar(
    envVars: List<EnvVar>,
    clazz: Class<out Feature>
) {
    this.filter { it.resource.kind == "DeploymentConfig" }.forEach {
        it.sources.addIfNotNull(AuroraResourceSource(feature = clazz, comment = "Added env vars"))
        val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
        dc.allNonSideCarContainers.forEach { container ->
            container.env.addAll(envVars)
        }
    }
}

fun Set<AuroraResource>.addVolumesAndMounts(
    envVars: List<EnvVar> = emptyList(),
    volumes: List<Volume> = emptyList(),
    volumeMounts: List<VolumeMount> = emptyList(),
    clazz: Class<out Feature>
) {
    this.filter { it.resource.kind == "DeploymentConfig" }.forEach {
        it.sources.addIfNotNull(AuroraResourceSource(feature = clazz, comment = "Added env vars, volume mount, volume"))
        val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
        dc.spec.template.spec.volumes = dc.spec.template.spec.volumes.addIfNotNull(volumes)
        dc.allNonSideCarContainers.forEach { container ->
            container.volumeMounts = container.volumeMounts.addIfNotNull(volumeMounts)
            container.env = container.env.addIfNotNull(envVars)
        }
    }
}