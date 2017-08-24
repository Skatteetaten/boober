package no.skatteetaten.aurora.boober.service.openshift

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.web.client.RestTemplate


@Configuration
class OpenShiftResourceClientConfig(
        @Value("\${openshift.url}") val baseUrl: String,
        val restTemplate: RestTemplate,
        val userDetailsTokenProvider: UserDetailsTokenProvider,
        val serviceAccountTokenProvider: ServiceAccountTokenProvider
) {

    enum class TokenSource {
        SERVICE_ACCOUNT,
        API_USER
    }

    @Target(AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
    @Retention(AnnotationRetention.RUNTIME)
    @Qualifier
    annotation class ClientType(val value: TokenSource)

    @Bean
    @ClientType(TokenSource.API_USER)
    @Primary
    fun createUserDetailsOpenShiftResourceClient(): OpenShiftResourceClient
            = OpenShiftResourceClient(baseUrl, userDetailsTokenProvider, restTemplate)

    @Bean
    @ClientType(TokenSource.SERVICE_ACCOUNT)
    fun createServiceAccountOpenShiftResourceClient(): OpenShiftResourceClient
            = OpenShiftResourceClient(baseUrl, serviceAccountTokenProvider, restTemplate)
}