package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.DeployBundleService
import no.skatteetaten.aurora.boober.service.filterDefaultFields
import no.skatteetaten.aurora.boober.service.renderJsonForAuroraDeploymentSpecPointers
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/auroradeployspec/{affiliation}/{environment}/{application}")
class AuroraDeploymentSpecControllerV1(val deployBundleService: DeployBundleService) {

    @GetMapping()
    fun get(
            @PathVariable affiliation: String,
            @PathVariable environment: String,
            @PathVariable application: String,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {
        val spec = deployBundleService.createAuroraDeploymentSpec(affiliation, ApplicationId.aid(environment, application), emptyList())

        val filteredFields = if (includeDefaults) spec.fields else filterDefaultFields(spec.fields)

        return Response(items = listOf(filteredFields))
    }

    @GetMapping("/formatted")
    fun getJsonForMapOfPointers(
            @PathVariable affiliation: String,
            @PathVariable environment: String,
            @PathVariable application: String,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {
        val spec = deployBundleService.createAuroraDeploymentSpec(affiliation, ApplicationId.aid(environment, application), emptyList())
        val formatted = renderJsonForAuroraDeploymentSpecPointers(spec, includeDefaults)
        return Response(items = listOf(formatted))
    }
}