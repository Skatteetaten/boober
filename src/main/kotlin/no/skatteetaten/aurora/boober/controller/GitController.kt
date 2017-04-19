package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.service.GitService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class GitController(val service: GitService) {

    @GetMapping("/gitgud")
    fun git(): String {
        val files: Map<String, Map<String, Any?>> = mapOf(
            "console.json" to
            mapOf("name" to "console", "type" to "deploy"),
            "utv2/console.json" to
                    mapOf("build" to mapOf("VERSION" to "2"))
        )

        service.saveFiles("paas", "utv-boober", files)
        return "OK"
    }
}