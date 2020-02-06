package no.skatteetaten.aurora.boober.utils

import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig

fun DeploymentConfig.findImageChangeTriggerTagName(): String? {
    return this.spec?.triggers
        ?.firstOrNull { it.type == "ImageChange" }
        ?.imageChangeParams?.from?.name
        ?.split(":")
        ?.takeIf { it.size == 2 }
        ?.last()
}

val Deployment.allNonSideCarContainers: List<Container>
    get() =
        this.spec.template.spec.containers.filter { !it.name.endsWith("sidecar") }

val DeploymentConfig.allNonSideCarContainers: List<Container>
    get() =
        this.spec.template.spec.containers.filter { !it.name.endsWith("sidecar") }
