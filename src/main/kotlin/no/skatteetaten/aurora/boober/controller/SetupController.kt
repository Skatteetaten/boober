package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.internal.SetupCommand
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.facade.SetupFacade
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.service.internal.ApplicationResult
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/affiliation")
class SetupController(val setupFacade: SetupFacade, val auroraConfigFacade: AuroraConfigFacade) {

    @PutMapping("/{affiliation}/deploy")
    fun deploy(@PathVariable affiliation: String, @RequestBody cmd: SetupCommand): Response {

        val setupParams = cmd.setupParams.toSetupParams()

        val auroraConfig = auroraConfigFacade.findAuroraConfig(affiliation)

        return executeSetup(auroraConfig, setupParams.applicationIds)
    }

    @PutMapping("/{affiliation}/setup")
    fun setup(@PathVariable affiliation: String, @RequestBody cmd: SetupCommand): Response {

        val setupParams = cmd.setupParams.toSetupParams()

        //TODO: Her må vi gi bedre feilmelding hvis det smeller.
        val auroraConfig: AuroraConfig = cmd.auroraConfig!!.toAuroraConfig()

        return executeSetup(auroraConfig, setupParams.applicationIds)
    }

    private fun executeSetup(auroraConfig: AuroraConfig, applicationIds: List<DeployCommand>): Response {

        val applicationResults: List<ApplicationResult> =
                setupFacade.executeSetup(auroraConfig, applicationIds)
        return Response(items = applicationResults)
    }
}
