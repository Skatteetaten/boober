package no.skatteetaten.aurora.boober.controller

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.service.ConfigService
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.service.GitService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File


@RestController
class InfoController(val gitService: GitService, val configService: ConfigService) {

    // Test : wget localhost:8080/api/getInfo/asdf/aot/utv/referanse
    @RequestMapping("/api/getInfo/{token}/{affiliation}/{env}/{app}")
    fun getInfo(@PathVariable token: String,
                @PathVariable affiliation: String,
                @PathVariable env: String,
                @PathVariable app: String): Result {

        val dir = File("/tmp/$token/$affiliation")

        gitService.get(dir)

        //val files = listOf("about.json", "$env/about.json", "$app.json", "$env/$app.json")

        //val matchedOverrides: Map<String, JsonNode> = emptyMap()

        // TODO: Must collect files from git and replace mapOf
        return configService.createBooberResult(env, app, mapOf())

    }
}