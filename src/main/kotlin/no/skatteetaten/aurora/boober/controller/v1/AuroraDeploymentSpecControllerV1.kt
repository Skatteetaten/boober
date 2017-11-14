package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.DeployBundleService
import no.skatteetaten.aurora.boober.service.createMapForAuroraDeploymentSpecPointers
import no.skatteetaten.aurora.boober.service.renderJsonForAuroraDeploymentSpecPointers
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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
        val mapOfPointers = createMapForAuroraDeploymentSpecPointers(spec, includeDefaults)

        return Response(items = listOf(mapOfPointers))
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