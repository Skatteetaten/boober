@file:JvmName("Main")

package no.skatteetaten.aurora.boober

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cache.annotation.EnableCaching

@SpringBootApplication
@EnableCaching
class Boober

fun main(args: Array<String>) {
    SpringApplication.run(Boober::class.java, *args)
}

