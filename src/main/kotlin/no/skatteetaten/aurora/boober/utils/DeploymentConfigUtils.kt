package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse

fun DeploymentConfig.from(openShiftResponse: OpenShiftResponse): DeploymentConfig =
        jacksonObjectMapper().readValue(openShiftResponse.responseBody.toString())