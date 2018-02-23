package no.skatteetaten.aurora.boober

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import no.skatteetaten.aurora.filter.logging.AuroraHeaderFilter
import no.skatteetaten.aurora.filter.logging.RequestKorrelasjon
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.ssl.SSLContexts
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import org.encryptor4j.factory.AbsKeyFactory
import org.encryptor4j.factory.KeyFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.retry.annotation.EnableRetry
import org.springframework.web.client.RestTemplate
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
class Configuration {

    @Bean
    @Primary
    fun mapper(): ObjectMapper {
        return jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    @Bean
    fun velocity(): VelocityEngine {
        return VelocityEngine().apply {
            setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath")
            setProperty("classpath.resource.loader.class", ClasspathResourceLoader::class.java.name)
            init()
        }
    }

    @Bean
    fun keyFactory(): KeyFactory = object : AbsKeyFactory("AES", 128) {}

    @Bean
    @Primary
    @TargetService(ServiceTypes.GENERAL)
    fun defaultRestTemplate(
        @Value("\${boober.httpclient.readTimeout:10000}") readTimeout: Int,
        @Value("\${boober.httpclient.connectTimeout:5000}") connectTimeout: Int
    ): RestTemplate {

        val clientHttpRequestFactory = defaultHttpComponentsClientHttpRequestFactory(readTimeout, connectTimeout)
        return RestTemplate(clientHttpRequestFactory)
    }

    @Bean
    @Qualifier("bitbucket")
    @TargetService(ServiceTypes.BITBUCKET)
    fun bitbucketRestTemplate(@Value("\${boober.httpclient.readTimeout:10000}") readTimeout: Int,
                              @Value("\${boober.httpclient.connectTimeout:5000}") connectTimeout: Int,
                              @Value("\${boober.git.username}") username: String,
                              @Value("\${boober.git.password}") password: String,
                              @Value("\${boober.bitbucket.url}") bitbucketUrl: String

    ): RestTemplate {

        val clientHttpRequestFactory = defaultHttpComponentsClientHttpRequestFactory(readTimeout, connectTimeout)
        return RestTemplateBuilder()
            .requestFactory(clientHttpRequestFactory)
            .rootUri(bitbucketUrl)
            .basicAuthorization(username, password)
            .build()
    }

    @Bean
    @TargetService(ServiceTypes.AURORA)
    fun auroraRestTemplate(@Value("\${boober.httpclient.readTimeout:10000}") readTimeout: Int,
                           @Value("\${boober.httpclient.connectTimeout:5000}") connectTimeout: Int,
                           @Value("\${spring.application.name}") applicationName: String,
                           sharedSecretReader: SharedSecretReader
    ): RestTemplate {

        val clientIdHeaderName = "KlientID"

        val clientHttpRequestFactory = defaultHttpComponentsClientHttpRequestFactory(readTimeout, connectTimeout)
        return RestTemplateBuilder()
            .requestFactory(clientHttpRequestFactory)
            .interceptors(ClientHttpRequestInterceptor { request, body, execution ->
                request.headers.apply {
                    set(HttpHeaders.AUTHORIZATION, "Bearer aurora-token ${sharedSecretReader.secret}")
                    set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    set(AuroraHeaderFilter.KORRELASJONS_ID, RequestKorrelasjon.getId())
                    set(clientIdHeaderName, applicationName)
                }

                execution.execute(request, body)
            })
            .build()
    }

    private fun defaultHttpComponentsClientHttpRequestFactory(readTimeout: Int, connectTimeout: Int): HttpComponentsClientHttpRequestFactory {
        return HttpComponentsClientHttpRequestFactory().apply {
            setReadTimeout(readTimeout)
            setConnectTimeout(connectTimeout)
            httpClient = createSslTrustAllHttpClient()
        }
    }

    private fun createSslTrustAllHttpClient(): CloseableHttpClient? {
        val acceptingTrustStrategy = { chain: Array<X509Certificate>, authType: String -> true }

        val sslContext = SSLContexts.custom()
            .loadTrustMaterial(null, acceptingTrustStrategy)
            .build()

        val csf = SSLConnectionSocketFactory(sslContext)

        val httpClient = HttpClients.custom()
            .setSSLSocketFactory(csf)
            .build()
        return httpClient
    }
}