package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.internal.SetupCommand
import no.skatteetaten.aurora.boober.facade.SetupFacade
import no.skatteetaten.aurora.boober.service.internal.AuroraApplicationCommand
import no.skatteetaten.aurora.boober.service.internal.AuroraApplicationResult
import no.skatteetaten.aurora.boober.service.internal.DeployHistory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/affiliation")
class SetupController(val setupFacade: SetupFacade) {

    @PutMapping("/{affiliation}/deploy")
    fun deploy(@PathVariable affiliation: String, @RequestBody cmd: SetupCommand): Response {

        val setupParams = cmd.setupParams.toSetupParams()
        val auroraApplicationResults: List<AuroraApplicationResult> = setupFacade.executeSetup(affiliation, setupParams)
        return Response(items = auroraApplicationResults)
    }

    @GetMapping("/{affiliation}/deploy")
    fun deployHistory(@PathVariable affiliation: String): Response {

        val applicationResults: List<DeployHistory> = setupFacade.deployHistory(affiliation)
        return Response(items = applicationResults)
    }

    @PutMapping("/{affiliation}/deploy/dryrun")
    fun deployDryRun(@PathVariable affiliation: String, @RequestBody cmd: SetupCommand): Response {

        val setupParams = cmd.setupParams.toSetupParams()
        val auroraApplicationResults: List<AuroraApplicationCommand> = setupFacade.dryRun(affiliation, setupParams)
        return Response(items = auroraApplicationResults)
    }
}
