package no.skatteetaten.aurora.boober.controller

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.service.ConfigService
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.service.GitService
import org.springframework.web.bind.annotation.*
import java.io.File


@RestController
class MockAocController(val gitService: GitService, val configService: ConfigService) {

    // Test : wget localhost:8080/api/setup/asdf/utv/myapp
    @RequestMapping("/api/setupMock/{env}/{app}")
    fun setupMock(@RequestHeader token: String,
                @PathVariable env: String,
                @PathVariable app: String,
                @RequestBody files: List<Map<String, JsonNode>>
                ): Result {

        println("setup called")
        println("Token: " + token)
        println("Env: " + env)
        println("App: " + app)

        for (file in files) {
            println(file.keys)
            println(file.values)
        }

        val retVal: Map<String, JsonNode> = emptyMap()

        return Result(sources = retVal)


    }
}