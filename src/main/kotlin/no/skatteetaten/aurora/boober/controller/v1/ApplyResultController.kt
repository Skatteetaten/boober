package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.DeployHistory
import no.skatteetaten.aurora.boober.service.DeployLogService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/apply-result/{auroraConfigName}")
class ApplyResultController(val deployLogService: DeployLogService) {

    @GetMapping("/")
    fun deployHistory(@PathVariable affiliation: String): Response {

        val applicationResults: List<DeployHistory> = deployLogService.deployHistory(affiliation)
        return Response(items = applicationResults)
    }

    @GetMapping("/{deployId}")
    fun findById(@PathVariable auroraConfigName: String, @PathVariable deployId: String): ResponseEntity<Response> {

        val deployResult = deployLogService.findDeployResultById(auroraConfigName, deployId)
        return deployResult?.let {
            ResponseEntity(Response(items = listOf(deployResult)), HttpStatus.OK)
        } ?: ResponseEntity<Response>(HttpStatus.NOT_FOUND)
    }
}
