package no.skatteetaten.aurora.boober.service.openshift

import no.skatteetaten.aurora.boober.service.openshift.token.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.service.openshift.token.UserDetailsTokenProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment

@Configuration
class OpenShiftResourceClientConfig(
    val restTemplateWrapper: OpenShiftRestTemplateWrapper,
    val userDetailsTokenProvider: UserDetailsTokenProvider,
    val serviceAccountTokenProvider: ServiceAccountTokenProvider,
    val environment: Environment
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
    fun createUserDetailsOpenShiftResourceClient(): OpenShiftResourceClient = OpenShiftResourceClient(
        userDetailsTokenProvider,
        restTemplateWrapper
    )

    @Bean
    @ClientType(TokenSource.SERVICE_ACCOUNT)
    fun createServiceAccountOpenShiftResourceClient(): OpenShiftResourceClient {
        return OpenShiftResourceClient(serviceAccountTokenProvider, restTemplateWrapper)
    }
}
