@file:JvmName("Main")
package no.skatteetaten.aurora.boober

import no.skatteetaten.aurora.annotations.AuroraApplication
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cache.annotation.EnableCaching

@AuroraApplication
@SpringBootApplication
@EnableCaching
class Application

fun main(args: Array<String>) {
    SpringApplication.run(Application::class.java, *args)
}
