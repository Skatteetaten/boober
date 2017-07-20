package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.internal.SetupCommand
import no.skatteetaten.aurora.boober.facade.SetupFacade
import no.skatteetaten.aurora.boober.service.internal.ApplicationResult
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/affiliation")
class SetupController(val setupFacade: SetupFacade) {

    @PutMapping("/{affiliation}/deploy")
    fun deploy(@PathVariable affiliation: String, @RequestBody cmd: SetupCommand): Response {

        val setupParams = cmd.setupParams.toSetupParams()

        val applicationResults: List<ApplicationResult> = setupFacade.executeSetup(affiliation, setupParams)
        return Response(items = applicationResults)
    }

}
