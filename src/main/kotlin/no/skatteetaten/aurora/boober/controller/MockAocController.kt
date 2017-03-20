package no.skatteetaten.aurora.boober.controller

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.service.ConfigService
import no.skatteetaten.aurora.boober.service.GitService
import org.springframework.web.bind.annotation.*


@RestController
class MockAocController(val gitService: GitService, val configService: ConfigService) {

    // Test : wget localhost:8080/api/setup/asdf/utv/myapp
    @RequestMapping("/api/setupMock/{env}/{app}")
    fun setupMock(@RequestHeader token: String,
                  @PathVariable env: String,
                  @PathVariable app: String,
                  @RequestBody configs: Map<String, JsonNode>
    ): Result {

        println("setup called")
        println("Token: " + token)
        println("Env: " + env)
        println("App: " + app)

        for (config in configs.entries) {
            println(config.key)

            println("Size: " + config.value.size())
            println("Value: " + config.value)

            val files: Map<String, JsonNode>

            for (foo in config.value) {
                println(foo)
            }

        }

        val retVal: Map<String, JsonNode> = emptyMap()

        return Result(sources = retVal)


    }
}