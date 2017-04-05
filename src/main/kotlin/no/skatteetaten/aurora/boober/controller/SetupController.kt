package no.skatteetaten.aurora.boober.controller


import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.ApplicationResult
import no.skatteetaten.aurora.boober.service.SetupService
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class SetupController(val setupService: SetupService) {

    @PutMapping("/setup")
    fun setup(@RequestBody cmd: SetupCommand): Response {

        return executeSetup(cmd)
    }

    @PutMapping("/setup-dryrun")
    fun setupDryRun(@RequestBody cmd: SetupCommand): Response {

        return executeSetup(cmd, true)
    }

    private fun executeSetup(cmd: SetupCommand, dryRun: Boolean = false): Response {

        val auroraConfig = AuroraConfig(cmd.files, cmd.secrets)
        val applicationResults: List<ApplicationResult> = setupService.executeSetup(auroraConfig, cmd.envs, cmd.apps)
        return Response(items = applicationResults)
    }
}

data class SetupCommand(val affiliation: String,
                        val envs: List<String> = listOf(),
                        val apps: List<String> = listOf(),
                        val files: Map<String, Map<String, Any?>>,
                        val secrets: Map<String, String> = mapOf(),
                        val overrides: Map<String, Map<String, Any?>>?)