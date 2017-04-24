package no.skatteetaten.aurora.boober.controller


import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.ApplicationResult
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.SetupService
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class SetupController(val setupService: SetupService, val auroraConfigService: AuroraConfigService) {

    @PutMapping("/deploy")
    fun deploy(@RequestBody cmd: SetupCommand): Response {

        val auroraConfig = auroraConfigService.findAuroraConfigForAffiliation(cmd.affiliation)
        val applicationResults: List<ApplicationResult> = setupService.executeSetup(auroraConfig, cmd.overrides, cmd.envs, cmd.apps)
        return Response(items = applicationResults)
    }

    @PutMapping("/setup")
    fun setup(@RequestBody cmd: SetupCommand): Response {

        return executeSetup(cmd)
    }


    @PutMapping("/setup-dryrun")
    fun setupDryRun(@RequestBody cmd: SetupCommand): Response {

        return executeSetup(cmd, true)
    }

    private fun executeSetup(cmd: SetupCommand, dryRun: Boolean = false): Response {

        val auroraConfig = AuroraConfig(cmd.files, cmd.secretFiles)
        val applicationResults: List<ApplicationResult> = setupService.executeSetup(auroraConfig, cmd.overrides, cmd.envs, cmd.apps)
        return Response(items = applicationResults)
    }
}
