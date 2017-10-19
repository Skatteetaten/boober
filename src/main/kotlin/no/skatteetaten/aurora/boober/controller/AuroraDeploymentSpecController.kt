package no.skatteetaten.aurora.boober.controller

import io.micrometer.core.annotation.Timed
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.DeployBundleService
import no.skatteetaten.aurora.boober.service.createMapForAuroraDeploymentSpecPointers
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auroradeployspec")
class AuroraDeploymentSpecController(val deployBundleService: DeployBundleService) {

    @Timed
    @GetMapping("/{affiliation}/{environment}/{application}")
    fun get(@PathVariable affiliation: String, @PathVariable environment: String, @PathVariable application: String): Response {

        val spec = deployBundleService.createAuroraDeploymentSpec(affiliation, ApplicationId.aid(environment, application), emptyList())
        val rendered = createMapForAuroraDeploymentSpecPointers(spec)

        return Response(items = listOf(rendered))
    }
}
