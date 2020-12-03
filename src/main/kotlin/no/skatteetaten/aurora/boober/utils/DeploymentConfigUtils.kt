package no.skatteetaten.aurora.boober.utils

import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.batch.CronJob
import io.fabric8.kubernetes.api.model.batch.Job
import io.fabric8.openshift.api.model.DeploymentConfig

fun DeploymentConfig.findImageChangeTriggerTagName(): String? {
    return this.spec?.triggers
        ?.firstOrNull { it.type == "ImageChange" }
        ?.imageChangeParams?.from?.name
        ?.split(":")
        ?.takeIf { it.size == 2 }
        ?.last()
}

fun HasMetadata.containersWithName(name: String): List<Container> {
    val containers = when (this) {
        is Deployment -> this.spec.template.spec.containers
        is DeploymentConfig -> this.spec.template.spec.containers
        is Job -> this.spec.template.spec.containers
        is CronJob -> this.spec.jobTemplate.spec.template.spec.containers
        else -> emptyList()
    }
    return containers.filter { it.name == name }
}

val HasMetadata.allNonSideCarContainers: List<Container>
    get() {
        return when (this) {
            is Deployment -> this.allNonSideCarContainers
            is DeploymentConfig -> this.allNonSideCarContainers
            is Job -> this.spec.template.spec.containers.filter { !it.name.endsWith("sidecar") }
            is CronJob -> this.spec.jobTemplate.spec.template.spec.containers.filter { !it.name.endsWith("sidecar") }
            else -> emptyList()
        }
    }

val Deployment.allNonSideCarContainers: List<Container>
    get() =
        this.spec.template.spec.containers.filter { !it.name.endsWith("sidecar") }

val DeploymentConfig.allNonSideCarContainers: List<Container>
    get() =
        this.spec.template.spec.containers.filter { !it.name.endsWith("sidecar") }
