package no.skatteetaten.aurora.boober.controller

import io.micrometer.core.annotation.Timed
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
@RequestMapping("/auroradeployspec")
class AuroraDeploymentSpecController(val deployBundleService: DeployBundleService) {

    @Timed
    @GetMapping("/{affiliation}/{environment}/{application}")
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

    @Timed
    @GetMapping("/{affiliation}/{environment}/{application}/formatted")
    fun getJsonForMapOfPointers(
            @PathVariable affiliation: String,
            @PathVariable environment: String,
            @PathVariable application: String,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): String {
        val spec = deployBundleService.createAuroraDeploymentSpec(affiliation, ApplicationId.aid(environment, application), emptyList())
        return renderJsonForAuroraDeploymentSpecPointers(spec, includeDefaults)
    }
}
