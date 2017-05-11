package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.internal.ApplicationResult
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.SetupService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/affiliation")
class SetupController(val setupService: SetupService, val auroraConfigService: AuroraConfigService) {

    @PutMapping("/{affiliation}/deploy")
    fun deploy(@PathVariable affiliation: String, @RequestBody cmd: SetupCommand): Response {

        val auroraConfig = auroraConfigService.findAuroraConfig(affiliation)
        return executeSetup(auroraConfig, cmd.setupParams.toSetupParams())
    }

    @PutMapping("/{affiliation}/setup")
    fun setup(@PathVariable affiliation: String, @RequestBody cmd: SetupCommand): Response {

        val auroraConfig: AuroraConfig = cmd.auroraConfig!!.toAuroraConfig()
        return executeSetup(auroraConfig, cmd.setupParams.toSetupParams())
    }

    @PutMapping("/{affiliation}/validate")
    fun setupDryRun(@PathVariable affiliation: String, @RequestBody cmd: SetupCommand): Response {

        val dryRunCmd = cmd.copy(setupParams = cmd.setupParams.copy(dryRun = true))
        return setup(affiliation, dryRunCmd)
    }

    private fun executeSetup(auroraConfig: AuroraConfig, setupParams: SetupParams): Response {

        val applicationResults: List<ApplicationResult> =
                setupService.executeSetup(auroraConfig, setupParams.applicationIds, setupParams.overrides)
        return Response(items = applicationResults)
    }
}
