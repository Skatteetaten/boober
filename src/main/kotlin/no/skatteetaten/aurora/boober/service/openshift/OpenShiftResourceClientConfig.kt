package no.skatteetaten.aurora.boober.service.openshift

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate

@Configuration
class OpenShiftResourceClientConfig(
        @Value("\${openshift.url}") val baseUrl: String,
        val restTemplate: RestTemplate,
        val userDetailsTokenProvider: UserDetailsTokenProvider,
        val serviceAccountTokenProvider: ServiceAccountTokenProvider
) {

    @Bean
    fun createUserDetailsOpenShiftResourceClient(): OpenShiftResourceClient {

        return OpenShiftResourceClient(baseUrl, userDetailsTokenProvider, restTemplate)
    }
}