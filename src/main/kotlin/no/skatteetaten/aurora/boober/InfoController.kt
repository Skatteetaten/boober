package no.skatteetaten.aurora.boober

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File


@RestController
class InfoController(val gitService: GitService, val configService: ConfigService) {

    @RequestMapping("/api/getInfo/{token}/{affiliation}/{env}/{app}")
    fun getInfo(@PathVariable token: String,
                @PathVariable affiliation: String,
                @PathVariable env: String,
                @PathVariable app: String): Result {

        val dir = File("/tmp/$token/$affiliation")

        gitService.get(dir)

        val files = listOf("about.json", "$env/about.json", "$app.json", "$env/$app.json")

        val matchedOverrides: Map<String, JsonNode> = emptyMap()

        return configService.createBooberResult(dir, files, matchedOverrides)

    }
}