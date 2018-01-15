@file:JvmName("Main")

package no.skatteetaten.aurora.boober

import io.micrometer.spring.autoconfigure.export.StringToDurationConverter
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Import

@SpringBootApplication
@EnableCaching
@Import(StringToDurationConverter::class)
class Application

fun main(args: Array<String>) {
    System.setProperty("spring.security.strategy", "MODE_INHERITABLETHREADLOCAL")
    SpringApplication.run(Application::class.java, *args)
}
