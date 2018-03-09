package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.fabric8.openshift.api.model.DeploymentConfig

fun DeploymentConfig.findImageChangeTriggerTagName(): String? {
    return this.spec?.triggers
            ?.firstOrNull { it.type == "ImageChange" }
            ?.imageChangeParams?.from?.name
            ?.split(":")
            ?.takeIf { it.size == 2 }
            ?.last()
}

fun deploymentConfigFromJson(jsonNode: JsonNode?): DeploymentConfig =
        jacksonObjectMapper().readValue(jsonNode.toString())