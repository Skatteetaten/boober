package no.skatteetaten.aurora.boober.service.openshift

import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

@Component
class OpenShiftRestTemplateWrapper(
    @TargetService(ServiceTypes.OPENSHIFT) restTemplate: RestTemplate
) : RetryingRestTemplateWrapper(restTemplate)