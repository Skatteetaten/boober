package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.ApplyPayload
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.DeployService
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/apply/{affiliation}")
class DeployControllerV1(val deployService: DeployService) {

    @PutMapping()
    fun apply(@PathVariable affiliation: String, @RequestBody payload: ApplyPayload): Response {

        val auroraDeployResults: List<AuroraDeployResult> = deployService.executeDeploy(affiliation, payload.applicationIds, payload.overridesToAuroraConfigFiles(), payload.deploy)
        val success = !auroraDeployResults.any { !it.success }
        return Response(items = auroraDeployResults, success = success)
    }
}