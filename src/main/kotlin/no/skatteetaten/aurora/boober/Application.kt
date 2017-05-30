package no.skatteetaten.aurora.boober

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import ske.aurora.annotations.AuroraApplication

@AuroraApplication
@SpringBootApplication
class Main

fun main(args: Array<String>) {
    SpringApplication.run(Main::class.java, *args)
}
