package no.skatteetaten.aurora.boober.controller


import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.ApplicationResult
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.SetupParams
import no.skatteetaten.aurora.boober.service.SetupService
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController


@RestController
class SetupController(val setupService: SetupService, val auroraConfigService: AuroraConfigService) {

    @PutMapping("/deploy")
    fun deploy(@RequestBody cmd: SetupCommand): Response {

        val auroraConfig = auroraConfigService.findAuroraConfig(cmd.affiliation)
        return executeSetup(auroraConfig, cmd.setupParams.toSetupParams())
    }

    @PutMapping("/setup")
    fun setup(@RequestBody cmd: SetupCommand): Response {

        val auroraConfig: AuroraConfig = cmd.auroraConfig!!.toAuroraConfig()
        return executeSetup(auroraConfig, cmd.setupParams.toSetupParams())
    }

    @PutMapping("/setup-dryrun")
    fun setupDryRun(@RequestBody cmd: SetupCommand): Response {

        val dryRunCmd = cmd.copy(setupParams = cmd.setupParams.copy(dryRun = true))
        return setup(dryRunCmd)
    }

    private fun executeSetup(auroraConfig: AuroraConfig, setupParams: SetupParams): Response {
        val applicationResults: List<ApplicationResult> = setupService.executeSetup(auroraConfig, setupParams)
        return Response(items = applicationResults)
    }
}
