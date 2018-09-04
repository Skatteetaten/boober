package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.DeployHistory
import no.skatteetaten.aurora.boober.service.DeployLogService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/apply-result/{auroraConfigName}")
class ApplyResultController(val deployLogService: DeployLogService) {

    @GetMapping("/")
    fun deployHistory(
        @PathVariable auroraConfigName: String
    ): Response {
        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val applicationResults: List<DeployHistory> = deployLogService.deployHistory(ref)
        return Response(items = applicationResults)
    }

    // TODO: This can be removed once all objects are converted.
    @PostMapping("/convert")
    fun convertHistory(
        @PathVariable auroraConfigName: String
    ): Response {
        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val applicationResults = deployLogService.getAllTags(ref).map {

            it
            /*deployLogService.storeDeployHistory(it)
                ?: throw RuntimeException("Could not store deploy history=${jacksonObjectMapper().writeValueAsString(it)}")
                */
        }
        return Response(items = applicationResults)
    }

    @GetMapping("/{deployId}")
    fun findById(
        @PathVariable auroraConfigName: String,
        @PathVariable deployId: String
    ): ResponseEntity<Response> {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val deployResult = deployLogService.findDeployResultById(ref, deployId)
        return deployResult?.let {
            ResponseEntity(Response(items = listOf(deployResult)), HttpStatus.OK)
        } ?: ResponseEntity(HttpStatus.NOT_FOUND)
    }
}
