package no.skatteetaten.aurora.boober.service.openshift

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment


@Configuration
class OpenShiftResourceClientConfig(
        @Value("\${openshift.url}") val baseUrl: String,
        val openShiftRequestHandler: OpenShiftRequestHandler,
        val userDetailsTokenProvider: UserDetailsTokenProvider,
        val serviceAccountTokenProvider: ServiceAccountTokenProvider,
        val localKubeConfigTokenProvider: LocalKubeConfigTokenProvider,
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
    fun createUserDetailsOpenShiftResourceClient(): OpenShiftResourceClient
            = OpenShiftResourceClient(baseUrl, userDetailsTokenProvider, openShiftRequestHandler)

    @Bean
    @ClientType(TokenSource.SERVICE_ACCOUNT)
    fun createServiceAccountOpenShiftResourceClient(): OpenShiftResourceClient {
        val tokenProvider = if (environment.activeProfiles.any { it == "local" }) {
            localKubeConfigTokenProvider
        } else {
            serviceAccountTokenProvider
        }
        return OpenShiftResourceClient(baseUrl, tokenProvider, openShiftRequestHandler)
    }
}