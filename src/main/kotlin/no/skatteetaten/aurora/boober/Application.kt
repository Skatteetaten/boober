package no.skatteetaten.aurora.boober

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary


@SpringBootApplication
class Main

fun main(args: Array<String>) {
    SpringApplication.run(Main::class.java, *args)
}

@Configuration
class Configuration {

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
}
