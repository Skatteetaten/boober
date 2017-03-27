package no.skatteetaten.aurora.boober

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.impl.client.HttpClients
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestTemplate
import java.security.cert.X509Certificate


@SpringBootApplication
class Main

fun main(args: Array<String>) {
    SpringApplication.run(Main::class.java, *args)
}

@Configuration
class Configuration {

    val logger: Logger = LoggerFactory.getLogger(Configuration::class.java)

    @Bean
    @Primary
    fun mapper(): ObjectMapper {
        return jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }

    @Bean
    fun velocity(): VelocityEngine {
        val ve  =  VelocityEngine()
        ve.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath")
        ve.setProperty("classpath.resource.loader.class", ClasspathResourceLoader::class.java.name)
        ve.init()

        return ve
    }

    @Bean
    @Primary
    fun restTemplate(): RestTemplate {

        logger.info("Loading custom rest template")
        return RestTemplate(clientHttpRequestFactory())
    }

    private fun clientHttpRequestFactory(): ClientHttpRequestFactory {
        val factory = HttpComponentsClientHttpRequestFactory()
        factory.setReadTimeout(2000)
        factory.setConnectTimeout(2000)

        val acceptingTrustStrategy = { chain: Array<X509Certificate>, authType: String -> true }

        val sslContext = org.apache.http.ssl.SSLContexts.custom()
                .loadTrustMaterial(null, acceptingTrustStrategy)
                .build()

        val csf = SSLConnectionSocketFactory(sslContext)

        val httpClient = HttpClients.custom()
                .setSSLSocketFactory(csf)
                .build()
        factory.httpClient = httpClient

        return factory
    }


}


