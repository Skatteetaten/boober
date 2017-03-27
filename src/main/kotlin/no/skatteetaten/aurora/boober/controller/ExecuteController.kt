package no.skatteetaten.aurora.boober.controller


import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.service.AocService
import no.skatteetaten.aurora.boober.service.SetupCommand
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class ExecuteController(val aocService: AocService) {

    val logger: Logger = LoggerFactory.getLogger(ExecuteController::class.java)

    @PutMapping("/setup")
    fun setup(@RequestHeader(value = "Authentication") rawToken: String,
              @RequestBody cmd: SetupCommand): Result {

        val token = rawToken.split(" ")[1]

        logger.info("Setting up ${cmd.app} in ${cmd.env} with token $token")

        return aocService.executeSetup(token, cmd)
    }
}