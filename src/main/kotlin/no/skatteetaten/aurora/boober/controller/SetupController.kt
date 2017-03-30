package no.skatteetaten.aurora.boober.controller


import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.SetupResult
import no.skatteetaten.aurora.boober.service.SetupService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class SetupController(val setupService: SetupService) {

    val logger: Logger = LoggerFactory.getLogger(SetupController::class.java)

    @PutMapping("/setup")
    fun setup(@RequestHeader(value = "Authentication") rawToken: String, @RequestBody cmd: SetupCommand): SetupResult {

        val token = rawToken.split(" ")[1]

        logger.info("Setting up ${cmd.app} in ${cmd.env} with token $token")

        val auroraConfig = AuroraConfig(cmd.files)
        return setupService.executeSetup(token, auroraConfig, cmd.env, cmd.app!!)
    }
}

data class SetupCommand(val affiliation: String,
                        val env: String,
                        val app: String?,
                        val files: Map<String, Map<String, Any?>>,
                        val overrides: Map<String, Map<String, Any?>>?)