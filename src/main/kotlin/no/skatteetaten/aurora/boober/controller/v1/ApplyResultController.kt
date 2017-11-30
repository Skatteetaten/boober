package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.DeployService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/apply-result/{auroraConfigId}")
class ApplyResultController(val deployService: DeployService) {

    @GetMapping("/{deployId}")
    fun findById(@PathVariable auroraConfigId: String, @PathVariable deployId: String): ResponseEntity<Response> {

        val deployResult = deployService.findDeployResultById(auroraConfigId, deployId)
        return deployResult?.let {
            ResponseEntity(Response(items = listOf(deployResult)), HttpStatus.OK)
        } ?: ResponseEntity<Response>(HttpStatus.NOT_FOUND)
    }
}
