package no.skatteetaten.aurora.boober.controller


import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.ApplicationResult
import no.skatteetaten.aurora.boober.service.SetupService
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class SetupController(val setupService: SetupService) {

    @PutMapping("/setup")
    fun setup(@RequestHeader(value = "Authentication") rawToken: String, @RequestBody cmd: SetupCommand): Response {

        val token = rawToken.split(" ")[1]

        val auroraConfig = AuroraConfig(cmd.files)
        val applicationResults: List<ApplicationResult> = setupService.executeSetup(token, auroraConfig, cmd.env, cmd.app!!)
        return Response(items = applicationResults)
    }

}

data class SetupCommand(val affiliation: String,
                        val env: String,
                        val app: String?,
                        val files: Map<String, Map<String, Any?>>,
                        val overrides: Map<String, Map<String, Any?>>?)