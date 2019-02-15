package no.skatteetaten.aurora.boober.utils

import io.fabric8.openshift.api.model.DeploymentConfig

fun DeploymentConfig.findImageChangeTriggerTagName(): String? {
    return this.spec?.triggers
        ?.firstOrNull { it.type == "ImageChange" }
        ?.imageChangeParams?.from?.name
        ?.split(":")
        ?.takeIf { it.size == 2 }
        ?.last()
}
