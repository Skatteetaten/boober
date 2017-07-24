package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.internal.SetupCommand
import no.skatteetaten.aurora.boober.facade.SetupFacade
import no.skatteetaten.aurora.boober.service.internal.ApplicationResult
import no.skatteetaten.aurora.boober.service.internal.DeployHistory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/affiliation")
class SetupController(val setupFacade: SetupFacade) {

    @PutMapping("/{affiliation}/deploy")
    fun deploy(@PathVariable affiliation: String, @RequestBody cmd: SetupCommand): Response {

        val setupParams = cmd.setupParams.toSetupParams()

        val applicationResults: List<ApplicationResult> = setupFacade.executeSetup(affiliation, setupParams)
        return Response(items = applicationResults)
    }

    @GetMapping("/{affiliation}/deploy")
    fun deployHistory(@PathVariable affiliation: String): Response {

        val applicationResults: List<DeployHistory> = setupFacade.deployHistory(affiliation)
        return Response(items = applicationResults)
    }
}
