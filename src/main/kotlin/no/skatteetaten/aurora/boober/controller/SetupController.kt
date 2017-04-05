package no.skatteetaten.aurora.boober.controller


import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.ApplicationResult
import no.skatteetaten.aurora.boober.service.SetupService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class SetupController(val setupService: SetupService) {

    @PutMapping("/setup")
    fun setup(@AuthenticationPrincipal activeUser: User, @RequestBody cmd: SetupCommand): Response {

        val auroraConfig = AuroraConfig(cmd.files)

        val applicationResults: List<ApplicationResult> = setupService.executeSetup(activeUser.token, auroraConfig, cmd.envs, cmd.apps)

        return Response(success = true, items = applicationResults)
    }
}

data class SetupCommand(val affiliation: String,
                        val envs: List<String> = listOf(),
                        val apps: List<String> = listOf(),
                        val files: Map<String, Map<String, Any?>>,
                        val overrides: Map<String, Map<String, Any?>>?)