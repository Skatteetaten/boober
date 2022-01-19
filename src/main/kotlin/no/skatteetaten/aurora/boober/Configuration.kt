package no.skatteetaten.aurora.boober

import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.openshift.token.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.service.openshift.token.UserDetailsTokenProvider
import no.skatteetaten.aurora.boober.utils.BooberHeaderRestTemplateCustomizer
import no.skatteetaten.aurora.boober.utils.SharedSecretReader
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.ssl.SSLContexts
import org.encryptor4j.factory.AbsKeyFactory
import org.encryptor4j.factory.KeyFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.retry.annotation.EnableRetry
import org.springframework.web.client.RestTemplate
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

@Configuration
@EnableRetry
@ConfigurationPropertiesScan
class Configuration {

    @Bean
    @ClientType(TokenSource.API_USER)
    @Primary
    fun createUserDetailsOpenShiftResourceClient(
        userDetailsTokenProvider: UserDetailsTokenProvider,
        restTemplateWrapper: OpenShiftRestTemplateWrapper
    ): OpenShiftResourceClient = OpenShiftResourceClient(
        userDetailsTokenProvider,
        restTemplateWrapper
    )

    @Bean
    @ClientType(TokenSource.SERVICE_ACCOUNT)
    fun createServiceAccountOpenShiftResourceClient(
        serviceAccountTokenProvider: ServiceAccountTokenProvider,
        restTemplateWrapper: OpenShiftRestTemplateWrapper
    ): OpenShiftResourceClient {
        return OpenShiftResourceClient(serviceAccountTokenProvider, restTemplateWrapper)
    }

    @Bean
    fun keyFactory(): KeyFactory = object : AbsKeyFactory("AES", 128) {}

    @Bean
    @Primary
    @TargetService(ServiceTypes.GENERAL)
    fun defaultRestTemplate(
        restTemplateBuilder: RestTemplateBuilder,
        clientHttpRequestFactory: HttpComponentsClientHttpRequestFactory
    ): RestTemplate {

        return restTemplateBuilder.requestFactory { clientHttpRequestFactory }.build()
    }

    @Bean
    @TargetService(ServiceTypes.OPENSHIFT)
    fun openShiftRestTemplate(
        restTemplateBuilder: RestTemplateBuilder,
        clientHttpRequestFactory: HttpComponentsClientHttpRequestFactory,
        @Value("\${integrations.openshift.url}") baseUrl: String
    ): RestTemplate {

        return restTemplateBuilder
            .rootUri(baseUrl)
            .requestFactory { clientHttpRequestFactory }.build()
    }

    @Bean
    @TargetDomain(Domain.AURORA_CONFIG)
    @Primary
    fun auroraConfigGitService(
        userDetails: UserDetailsProvider,
        metrics: AuroraMetrics,
        @Value("\${integrations.bitbucket.username}") username: String,
        @Value("\${integrations.bitbucket.password}") password: String,
        @Value("\${integrations.aurora.config.git.urlPattern}") urlPattern: String,
        @Value("\${integrations.aurora.config.git.checkoutPath}") checkoutPath: String
    ): GitService {
        return GitService(userDetails, urlPattern, checkoutPath, username, password, metrics)
    }

    @Bean
    @TargetDomain(Domain.VAULT)
    fun vaultGitService(
        userDetails: UserDetailsProvider,
        metrics: AuroraMetrics,
        @Value("\${integrations.bitbucket.username}") username: String,
        @Value("\${integrations.bitbucket.password}") password: String,
        @Value("\${integrations.aurora.vault.git.urlPattern}") urlPattern: String,
        @Value("\${integrations.aurora.vault.git.checkoutPath}") checkoutPath: String
    ): GitService {
        return GitService(userDetails, urlPattern, checkoutPath, username, password, metrics)
    }

    @Bean
    @Qualifier("bitbucket")
    @TargetService(ServiceTypes.BITBUCKET)
    fun bitbucketRestTemplate(
        restTemplateBuilder: RestTemplateBuilder,
        @Value("\${integrations.bitbucket.username}") username: String,
        @Value("\${integrations.bitbucket.password}") password: String,
        @Value("\${integrations.bitbucket.url}") bitbucketUrl: String,
        clientHttpRequestFactory: HttpComponentsClientHttpRequestFactory
    ): RestTemplate {

        return restTemplateBuilder
            .requestFactory { clientHttpRequestFactory }
            .rootUri(bitbucketUrl)
            .basicAuthentication(username, password)
            .build()
    }

    @Bean
    @TargetService(ServiceTypes.CANTUS)
    fun cantusRestTemplate(
        restTemplateBuilder: RestTemplateBuilder,
        @Value("\${boober.httpclient.readTimeout:10000}") readTimeout: Int,
        @Value("\${boober.httpclient.connectTimeout:5000}") connectTimeout: Int,
        @Value("\${spring.application.name}") applicationName: String,
        @Value("\${integrations.cantus.url}") cantusUrl: String,
        @Value("\${integrations.cantus.token}") cantusToken: String,
        clientHttpRequestFactory: HttpComponentsClientHttpRequestFactory
    ): RestTemplate {

        val clientIdHeaderName = "KlientID"

        return restTemplateBuilder
            .requestFactory { clientHttpRequestFactory }
            .rootUri(cantusUrl)
            .interceptors(
                ClientHttpRequestInterceptor { request, body, execution ->
                    request.headers.apply {
                        set(HttpHeaders.AUTHORIZATION, "Bearer $cantusToken")
                    }

                    execution.execute(request, body)
                }
            ).build()
    }

    @Bean
    @TargetService(ServiceTypes.AURORA)
    fun auroraRestTemplate(
        restTemplateBuilder: RestTemplateBuilder,
        @Value("\${spring.application.name}") applicationName: String,
        sharedSecretReader: SharedSecretReader,
        clientHttpRequestFactory: HttpComponentsClientHttpRequestFactory
    ): RestTemplate {
        val clientIdHeaderName = "KlientID"
        return restTemplateBuilder
            .requestFactory { clientHttpRequestFactory }
            .interceptors(
                ClientHttpRequestInterceptor { request, body, execution ->
                    request.headers.apply {
                        set(
                            HttpHeaders.AUTHORIZATION,
                            "aurora-token ${sharedSecretReader.secret}"
                        )
                        set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    }

                    execution.execute(request, body)
                }
            ).build()
    }

    @Bean
    fun defaultHttpComponentsClientHttpRequestFactory(
        @Value("\${boober.httpclient.readTimeout:10000}") readTimeout: Int,
        @Value("\${boober.httpclient.connectTimeout:5000}") connectTimeout: Int,
        httpClient: CloseableHttpClient
    ): HttpComponentsClientHttpRequestFactory = HttpComponentsClientHttpRequestFactory().apply {
        setReadTimeout(readTimeout)
        setConnectTimeout(connectTimeout)
        setHttpClient(httpClient)
    }

    @Bean
    fun closeableHttpClient(trustStore: KeyStore?): CloseableHttpClient {
        val sslContext = SSLContexts.custom()
            .loadTrustMaterial(trustStore) { _: Array<X509Certificate>, _: String -> false }
            .build()
        val build = HttpClients.custom()
            .setSSLSocketFactory(SSLConnectionSocketFactory(sslContext))
            .build()
        return build
    }

    @ConditionalOnMissingBean(KeyStore::class)
    @Bean
    fun localKeyStore(): KeyStore? = null

    @Profile("openshift")
    @Primary
    @Bean
    fun openshiftSSLContext(@Value("\${trust.store}") trustStoreLocation: String): KeyStore? =
        KeyStore.getInstance(KeyStore.getDefaultType())?.let { ks ->
            ks.load(FileInputStream(trustStoreLocation), "changeit".toCharArray())
            val fis = FileInputStream("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
            CertificateFactory.getInstance("X509").generateCertificates(fis).forEach {
                ks.setCertificateEntry((it as X509Certificate).subjectX500Principal.name, it)
            }
            ks
        }

    @Bean
    @Primary
    fun booberHeaderRestTemplateCustomizer() = BooberHeaderRestTemplateCustomizer()
}

enum class ServiceTypes {
    BITBUCKET, GENERAL, AURORA, OPENSHIFT, CANTUS
}

@Target(AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class TargetService(val value: ServiceTypes)

enum class TokenSource {
    SERVICE_ACCOUNT,
    API_USER
}

@Target(AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class ClientType(val value: TokenSource)

enum class Domain {
    AURORA_CONFIG,
    VAULT
}

@Target(AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class TargetDomain(val value: Domain)
