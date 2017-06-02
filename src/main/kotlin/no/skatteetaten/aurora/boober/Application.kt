package no.skatteetaten.aurora.boober

import no.skatteetaten.aurora.annotations.AuroraApplication
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@AuroraApplication
@SpringBootApplication
class Main

fun main(args: Array<String>) {
    SpringApplication.run(Main::class.java, *args)
}
