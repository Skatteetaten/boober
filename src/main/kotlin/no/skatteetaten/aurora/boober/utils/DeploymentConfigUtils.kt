package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.fabric8.openshift.api.model.DeploymentConfig

fun deploymentConfigFromJson(jsonNode: JsonNode?): DeploymentConfig =
        jacksonObjectMapper().readValue(jsonNode.toString())