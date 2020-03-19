package no.skatteetaten.aurora.boober.utils

import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.HasMetadata
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

val HasMetadata.allNonSideCarContainers: List<Container> get() {
   return when (this) {
       is DeploymentConfig ->  this.allNonSideCarContainers
       is Job ->  this.spec.template.spec.containers
       is CronJob ->  this.spec.jobTemplate.spec.template.spec.containers
       else -> emptyList()
   }
}

val DeploymentConfig.allNonSideCarContainers: List<Container>
    get() =
        this.spec.template.spec.containers.filter { !it.name.endsWith("sidecar") }
