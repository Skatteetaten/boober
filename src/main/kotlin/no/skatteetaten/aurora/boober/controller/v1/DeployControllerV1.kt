package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.ApplyPayload
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.DeployService
import no.skatteetaten.aurora.boober.service.internal.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.internal.DeployHistory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/apply/{affiliation}")
class DeployControllerV1(val deployService: DeployService) {

    @PutMapping()
    fun apply(@PathVariable affiliation: String, @RequestBody payload: ApplyPayload): Response {

        val auroraDeployResults: List<AuroraDeployResult> = deployService.executeDeploy(affiliation, payload.applicationIds, payload.overridesToAuroraConfigFiles(), payload.deploy)
        val success = !auroraDeployResults.any { !it.success }
        return Response(items = auroraDeployResults, success = success)
    }


    @GetMapping("/history")
    fun deployHistory(@PathVariable affiliation: String): Response {

        val applicationResults: List<DeployHistory> = deployService.deployHistory(affiliation)
        return Response(items = applicationResults)
    }
}