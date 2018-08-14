package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.ApplyPayload
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.DeployService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/apply/{auroraConfigName}")
class DeployControllerV1(val deployService: DeployService) {

    @PutMapping()
    fun apply(
        @PathVariable auroraConfigName: String,
        @RequestParam(name = "reference", required = false) reference: String?,
        @RequestBody payload: ApplyPayload
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest(reference))
        val auroraDeployResults: List<AuroraDeployResult> = deployService.executeDeploy(
            ref,
            payload.applicationIds,
            payload.overridesToAuroraConfigFiles(),
            payload.deploy
        )

        return auroraDeployResults.find { !it.success }
            ?.let { Response(items = auroraDeployResults, success = false, message = it.reason ?: "Deploy failed") }
            ?: Response(items = auroraDeployResults)
    }
}