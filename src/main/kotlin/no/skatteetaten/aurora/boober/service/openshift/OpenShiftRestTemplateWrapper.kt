package no.skatteetaten.aurora.boober.service.openshift

import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

@Component
class OpenShiftRestTemplateWrapper(restTemplate: RestTemplate) : RetryingRestTemplateWrapper(restTemplate)