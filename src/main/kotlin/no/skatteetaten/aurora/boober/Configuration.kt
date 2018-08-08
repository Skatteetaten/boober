package no.skatteetaten.aurora.boober

import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import no.skatteetaten.aurora.filter.logging.AuroraHeaderFilter
import no.skatteetaten.aurora.filter.logging.RequestKorrelasjon
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.ssl.SSLContexts
import org.encryptor4j.factory.AbsKeyFactory
import org.encryptor4j.factory.KeyFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
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
import java.security.cert.X509Certificate

enum class ServiceTypes {
    BITBUCKET, GENERAL, AURORA
}

@Target(AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class TargetService(val value: ServiceTypes)

@Configuration
@EnableRetry
class Configuration : BeanPostProcessor {

    override fun postProcessBeforeInitialization(bean: Any, beanName: String?): Any = bean

    override fun postProcessAfterInitialization(bean: Any, beanName: String?): Any {
        if (beanName == "_halObjectMapper" && bean is ObjectMapper) {
            configureObjectMapper(bean)
        }

        return bean
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

        return restTemplateBuilder.requestFactory(clientHttpRequestFactory).build()
    }

    @Bean
    @Qualifier("bitbucket")
    @TargetService(ServiceTypes.BITBUCKET)
    fun bitbucketRestTemplate(
        restTemplateBuilder: RestTemplateBuilder,
        @Value("\${boober.git.username}") username: String,
        @Value("\${boober.git.password}") password: String,
        @Value("\${boober.bitbucket.url}") bitbucketUrl: String,
        clientHttpRequestFactory: HttpComponentsClientHttpRequestFactory
    ): RestTemplate {

        return restTemplateBuilder
            .requestFactory(clientHttpRequestFactory)
            .rootUri(bitbucketUrl)
            .basicAuthorization(username, password)
            .build()
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
            .requestFactory(clientHttpRequestFactory)
            .interceptors(ClientHttpRequestInterceptor { request, body, execution ->
                request.headers.apply {
                    set(HttpHeaders.AUTHORIZATION, "Bearer aurora-token ${sharedSecretReader.secret}")
                    set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    set(AuroraHeaderFilter.KORRELASJONS_ID, RequestKorrelasjon.getId())
                    set(clientIdHeaderName, applicationName)
                }

                execution.execute(request, body)
            }).build()
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
    @Bean
    fun openshiftSSLContext(): KeyStore? = KeyStore.getInstance(KeyStore.getDefaultType())?.apply {
        load(FileInputStream("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"), "".toCharArray())
    }
}