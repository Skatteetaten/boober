package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.controller.internal.ApplyPayload
import no.skatteetaten.aurora.boober.controller.internal.DeployCommand
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.DeployService
import no.skatteetaten.aurora.boober.service.internal.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.internal.DeployHistory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/affiliation")
class DeployController(val deployService: DeployService) {

    val logger: Logger = LoggerFactory.getLogger(DeployController::class.java)

    @PutMapping("/{affiliation}/apply")
    fun apply(@PathVariable affiliation: String, @RequestBody payload: ApplyPayload): Response {

        val auroraDeployResults: List<AuroraDeployResult> = deployService.executeDeploy(affiliation, payload.applicationIds, payload.overridesToAuroraConfigFiles(), payload.deploy)
        val success = !auroraDeployResults.any { !it.success }
        return Response(items = auroraDeployResults, success = success)
    }

    @PutMapping("/{affiliation}/deploy")
    @Deprecated(message = "Use apply instead", replaceWith = ReplaceWith("apply(affiliation, _)"))
    fun deploy(@PathVariable affiliation: String, @RequestBody cmd: DeployCommand): Response {

        logger.debug("Staring deploy request")
        val setupParams = cmd.setupParams.toDeployParams()
        val auroraDeployResults: List<AuroraDeployResult> = deployService.executeDeploy(affiliation, setupParams.applicationIds, setupParams.overrides, setupParams.deploy)
        val success = !auroraDeployResults.any { !it.success }
        logger.debug("end deploy request")
        return Response(items = auroraDeployResults, success = success)
    }

    @GetMapping("/{affiliation}/deploy")
    fun deployHistory(@PathVariable affiliation: String): Response {

        val applicationResults: List<DeployHistory> = deployService.deployHistory(affiliation)
        return Response(items = applicationResults)
    }
}
