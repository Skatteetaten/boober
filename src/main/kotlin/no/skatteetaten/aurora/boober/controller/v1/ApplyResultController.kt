package no.skatteetaten.aurora.boober.controller.v1

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.DeployHistoryEntry
import no.skatteetaten.aurora.boober.service.DeployLogService

private val logger = KotlinLogging.logger {}
@RestController
@RequestMapping("/v1/apply-result/{auroraConfigName}")
class ApplyResultController(private val deployLogService: DeployLogService) {

    @GetMapping
    fun deployHistory(
        @PathVariable auroraConfigName: String
    ): Response {
        val user = deployLogService.userDetailsProvider.getAuthenticatedUser()
        logger.info("Retrieving deployHistory user=${user.username}")

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val applicationResults: List<DeployHistoryEntry> = deployLogService.deployHistory(ref)
        return Response(items = applicationResults)
    }

    @GetMapping("/{deployId}")
    fun findById(
        @PathVariable auroraConfigName: String,
        @PathVariable deployId: String
    ): ResponseEntity<Response> {

        val user = deployLogService.userDetailsProvider.getAuthenticatedUser()
        logger.info("Inspecting deploy deployId=$deployId user=${user.username}")
        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val deployResult = deployLogService.findDeployResultById(ref, deployId)
        return deployResult?.let {
            ResponseEntity(Response(item = deployResult), HttpStatus.OK)
        } ?: ResponseEntity(HttpStatus.NOT_FOUND)
    }
}
