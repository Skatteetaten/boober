package no.skatteetaten.aurora.boober.controller

import io.micrometer.core.annotation.Timed
import no.skatteetaten.aurora.boober.controller.internal.DeployCommand
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.DeployService
import no.skatteetaten.aurora.boober.service.internal.AuroraApplicationCommand
import no.skatteetaten.aurora.boober.service.internal.AuroraApplicationResult
import no.skatteetaten.aurora.boober.service.internal.DeployHistory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/affiliation")
class DeployController(val deployService: DeployService) {


    @Timed
    @PutMapping("/{affiliation}/deploy")
    fun deploy(@PathVariable affiliation: String, @RequestBody cmd: DeployCommand): Response {

        val setupParams = cmd.setupParams.toDeployParams()
        val auroraApplicationResults: List<AuroraApplicationResult> = deployService.executeDeploy(affiliation, setupParams)
        val success = !auroraApplicationResults.any { !it.success }
        return Response(items = auroraApplicationResults, success = success)
    }

    @Timed
    @GetMapping("/{affiliation}/deploy")
    fun deployHistory(@PathVariable affiliation: String): Response {

        val applicationResults: List<DeployHistory> = deployService.deployHistory(affiliation)
        return Response(items = applicationResults)
    }

    @Timed
    @PutMapping("/{affiliation}/deploy/dryrun")
    fun deployDryRun(@PathVariable affiliation: String, @RequestBody cmd: DeployCommand): Response {

        val setupParams = cmd.setupParams.toDeployParams()
        val auroraApplicationResults: List<AuroraApplicationCommand> = deployService.dryRun(affiliation, setupParams)
        return Response(items = auroraApplicationResults)
    }
}
