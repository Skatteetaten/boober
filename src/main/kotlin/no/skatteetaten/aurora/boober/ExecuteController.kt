package no.skatteetaten.aurora.boober


import com.fasterxml.jackson.databind.JsonNode
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.io.File

@RestController
class ExecuteController(val gitService: GitService, val configService: ConfigService) {


    @PostMapping("/setup/{token}/{affiliation}/{env}")
    fun setupNamespace(@PathVariable token: String,
                       @PathVariable affiliation: String,
                       @PathVariable env: String,
                       @RequestBody overrides: Map<String, JsonNode>): NamespaceResult {

        val dir = File("/tmp/$token/$affiliation")

        gitService.get(dir)

        val envDir = File(dir, env)
        val apps = envDir.listFiles({ _, name -> name != "about.json" }).toList().map(File::nameWithoutExtension)

        val res: Map<String, Result> = apps.map {
            val files = listOf("about.json", "$env/about.json", "$it.json", "$env/$it.json")
            val matchedOverrides = overrides.filter { it.key == "about.json" || it.key == "$it.json" }
            Pair(it, configService.createBooberResult(dir, files, matchedOverrides))
        }.toMap()

        return NamespaceResult(res)

    }

    @PostMapping("/setup/{token}/{affiliation}/{env}/{app}")
    fun setup(@PathVariable token: String,
              @PathVariable affiliation: String,
              @PathVariable env: String,
              @PathVariable app: String,
              @RequestBody overrides: Map<String, JsonNode>): Result {

        val dir = File("/tmp/$token/$affiliation")

        gitService.get(dir)

        val files = listOf("about.json", "$env/about.json", "$app.json", "$env/$app.json")

        val matchedOverrides = overrides.filter { it.key == "about.json" || it.key == "$app.json" }

        return configService.createBooberResult(dir, files, matchedOverrides)

    }

}
