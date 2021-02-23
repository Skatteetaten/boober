package no.skatteetaten.aurora.boober.service.openshift

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper

@Component
class OpenShiftRestTemplateWrapper(
    @TargetService(ServiceTypes.OPENSHIFT) restTemplate: RestTemplate,
    @Value("\${integrations.openshift.retries}") retries: Int = 3,
    @Value("\${integrations.openshift.backoff}") backoff: Long = 500
) : RetryingRestTemplateWrapper(restTemplate, retries, backoff)
