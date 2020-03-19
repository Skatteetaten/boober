package no.skatteetaten.aurora.boober.model

import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.kubernetes.api.model.batch.CronJob
import io.fabric8.kubernetes.api.model.batch.Job
import io.fabric8.openshift.api.model.DeploymentConfig
import java.time.Instant
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.allNonSideCarContainers

private val logger = KotlinLogging.logger {}

/*
  An dataclass to hold a HasMetadata resource that will be mutated in the generation process

  @param resource: Will be generated in one feature and then optionally mutated in other features
  @param header: flag if this resource is part of the header that will only be done once for all applications in a given deploy command
  @param sources: Set of sources to track what features have created a resource and what features that have changed it
 */
data class AuroraResource(
    val resource: HasMetadata,
    val createdSource: AuroraResourceSource,
    val header: Boolean = false, // these resources are only created once for each deploy){}){}
    val sources: MutableSet<AuroraResourceSource> = mutableSetOf()
)

data class AuroraResourceSource(
    val feature: Class<out Feature>,
    val comment: String? = "",
    val time: Instant = Instants.now
)

fun Set<AuroraResource>.addEnvVar(
    envVars: List<EnvVar>,
    clazz: Class<out Feature>
) {
    this.filter { it.resource.kind in listOf("DeploymentConfig", "Job", "CronJob") }
        .onEach { it.sources.add(AuroraResourceSource(feature = clazz, comment = "Added env vars")) }
        .flatMap { it.resource.allNonSideCarContainers }
        .forEach { container -> container.env.addAll(envVars) }
}

fun Set<AuroraResource>.addVolumesAndMounts(
    envVars: List<EnvVar> = emptyList(),
    volumes: List<Volume> = emptyList(),
    volumeMounts: List<VolumeMount> = emptyList(),
    clazz: Class<out Feature>
) {
    this.filter { it.resource.kind == "DeploymentConfig" }.forEach {
        it.sources.add(AuroraResourceSource(feature = clazz, comment = "Added env vars, volume mount, volume"))
        val dc: DeploymentConfig = it.resource as DeploymentConfig
        dc.spec.template.spec.volumes = dc.spec.template.spec.volumes.addIfNotNull(volumes)
        dc.allNonSideCarContainers.forEach { container ->
            container.volumeMounts = container.volumeMounts.addIfNotNull(volumeMounts)
            container.env = container.env.addIfNotNull(envVars)
        }
    }

    this.filter { it.resource.kind == "CronJob" }.forEach {
        it.sources.add(AuroraResourceSource(feature = clazz, comment = "Added env vars, volume mount, volume"))
        val job: CronJob = it.resource as CronJob
        job.spec.jobTemplate.spec.template.spec.volumes = job.spec.jobTemplate.spec.template.spec.volumes.addIfNotNull(volumes)
        job.allNonSideCarContainers.forEach { container ->
            container.volumeMounts = container.volumeMounts.addIfNotNull(volumeMounts)
            container.env = container.env.addIfNotNull(envVars)
        }
    }
    this.filter { it.resource.kind == "Job" }.forEach {
        it.sources.add(AuroraResourceSource(feature = clazz, comment = "Added env vars, volume mount, volume"))
        val job: Job = it.resource as Job
        job.spec.template.spec.volumes = job.spec.template.spec.volumes.addIfNotNull(volumes)
        job.allNonSideCarContainers.forEach { container ->
            container.volumeMounts = container.volumeMounts.addIfNotNull(volumeMounts)
            container.env = container.env.addIfNotNull(envVars)
        }
    }
}
