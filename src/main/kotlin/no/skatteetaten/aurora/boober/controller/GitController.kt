package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.service.GitService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File

@RestController
class GitController(val service: GitService) {

    @GetMapping("/gitgud")
    fun git(): String {
        val files: Map<String, Any> = mapOf("console.json" to
"""{
    "name": "console"
}""")
        service.saveFiles("paas", File("/home/m86862/boober"), files)
        return "OK"
    }
}