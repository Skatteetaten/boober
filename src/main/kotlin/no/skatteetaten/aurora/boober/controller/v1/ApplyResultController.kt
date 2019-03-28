package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.Responder
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.DeployLogService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/apply-result/{auroraConfigName}")
class ApplyResultController(private val deployLogService: DeployLogService, private val responder: Responder) {

    @GetMapping("/") // FIXME path should not end with /, causes issues with the wiremock pattern
    fun deployHistory(
        @PathVariable auroraConfigName: String
    ): Response {
        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val applicationResults: List<JsonNode> = deployLogService.deployHistory(ref)
        return responder.create(applicationResults)
    }

    @GetMapping("/{deployId}")
    fun findById(
        @PathVariable auroraConfigName: String,
        @PathVariable deployId: String
    ): ResponseEntity<Response> {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val deployResult = deployLogService.findDeployResultById(ref, deployId)
        return deployResult?.let {
            ResponseEntity(responder.create(deployResult), HttpStatus.OK)
        } ?: ResponseEntity(HttpStatus.NOT_FOUND)
    }
}
