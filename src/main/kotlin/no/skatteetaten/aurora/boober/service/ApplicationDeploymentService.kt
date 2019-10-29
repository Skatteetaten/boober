package no.skatteetaten.aurora.boober.service

import mu.KotlinLogging
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

// TODO: Fasade
@Service
class ApplicationDeploymentService(
    val openShiftClient: OpenShiftClient
) {





}
