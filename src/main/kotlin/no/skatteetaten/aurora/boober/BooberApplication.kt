package no.skatteetaten.aurora.boober

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class BooberApplication

fun main(args: Array<String>) {
    SpringApplication.run(BooberApplication::class.java, *args)
}
