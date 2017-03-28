package no.skatteetaten.aurora.boober.controller


import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AocConfig
import no.skatteetaten.aurora.boober.service.AocResult
import no.skatteetaten.aurora.boober.service.AocService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class AocController(val aocService: AocService) {

    val logger: Logger = LoggerFactory.getLogger(AocController::class.java)

    @PutMapping("/setup")
    fun setup(@RequestHeader(value = "Authentication") rawToken: String,
              @RequestBody cmd: SetupCommand): AocResult {

        val token = rawToken.split(" ")[1]

        logger.info("Setting up ${cmd.app} in ${cmd.env} with token $token")

        val aocConfig = AocConfig(cmd.files!!)
        return aocService.executeSetup(token, aocConfig, cmd.env, cmd.app!!)
    }
}

data class SetupCommand(val affiliation: String,
                        val env: String,
                        val app: String?,
                        val files: Map<String, JsonNode>?,
                        val overrides: Map<String, JsonNode>?)