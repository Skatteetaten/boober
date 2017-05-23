package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.internal.SetupCommand
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.facade.SetupFacade
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.internal.ApplicationResult
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/affiliation")
class SetupController(val setupFacade: SetupFacade, val auroraConfigFacade: AuroraConfigFacade) {

    @PutMapping("/{affiliation}/deploy")
    fun deploy(@PathVariable affiliation: String, @RequestBody cmd: SetupCommand): Response {

        val setupParams = cmd.setupParams.toSetupParams()
        val auroraConfig = auroraConfigFacade.findAuroraConfig(affiliation)
        auroraConfig.addOverrides(setupParams.overrides)

        return executeSetup(auroraConfig, setupParams.applicationIds)
    }

    @PutMapping("/{affiliation}/setup")
    fun setup(@PathVariable affiliation: String, @RequestBody cmd: SetupCommand): Response {

        val setupParams = cmd.setupParams.toSetupParams()
        val auroraConfig: AuroraConfig = cmd.auroraConfig!!.toAuroraConfig(setupParams.overrides)
        return executeSetup(auroraConfig, setupParams.applicationIds)
    }


    private fun executeSetup(auroraConfig: AuroraConfig, applicationIds: List<ApplicationId>): Response {

        val applicationResults: List<ApplicationResult> =
                setupFacade.executeSetup(auroraConfig, applicationIds)
        return Response(items = applicationResults)
    }
}
