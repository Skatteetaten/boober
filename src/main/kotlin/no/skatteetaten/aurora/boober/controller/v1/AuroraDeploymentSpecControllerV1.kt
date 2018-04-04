package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
import no.skatteetaten.aurora.boober.service.filterDefaultFields
import no.skatteetaten.aurora.boober.service.renderJsonForAuroraDeploymentSpecPointers
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/auroradeployspec/{auroraConfigName}")
class AuroraDeploymentSpecControllerV1(val auroraDeploymentSpecService: AuroraDeploymentSpecService) {

    @GetMapping("/")
    fun findAllDeploymentSpecs(
            @PathVariable auroraConfigName: String,
            @RequestParam(name = "aid", required = false) aidStrings: List<String>,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val fields = auroraDeploymentSpecService
                .getAuroraDeploymentSpecs(auroraConfigName, aidStrings)
                .map { filterDefaults(includeDefaults, it) }

        return Response(items = fields)
    }

    @GetMapping("/{environment}/")
    fun findAllDeploymentSpecsForEnvironment(
            @PathVariable auroraConfigName: String,
            @PathVariable environment: String,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val fields = auroraDeploymentSpecService
                .getAuroraDeploymentSpecsForEnvironment(auroraConfigName, environment)
                .map {filterDefaults(includeDefaults, it) }

        return Response(items = fields)
    }

    @GetMapping("/{environment}/{application}")
    fun get(
            @PathVariable auroraConfigName: String,
            @PathVariable environment: String,
            @PathVariable application: String,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val filteredFields = auroraDeploymentSpecService
                .getAuroraDeploymentSpec(auroraConfigName, environment, application)
                .let { filterDefaults(includeDefaults, it) }

        return Response(items = listOf(filteredFields))
    }

    private fun filterDefaults(includeDefaults: Boolean, spec: AuroraDeploymentSpec) =
            if (includeDefaults) spec.fields else filterDefaultFields(spec.fields)

    @GetMapping("/{environment}/{application}/formatted")
    fun getJsonForMapOfPointers(
            @PathVariable auroraConfigName: String,
            @PathVariable environment: String,
            @PathVariable application: String,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val spec = auroraDeploymentSpecService.getAuroraDeploymentSpec(auroraConfigName, environment, application)
        val formatted = renderJsonForAuroraDeploymentSpecPointers(spec, includeDefaults)
        return Response(items = listOf(formatted))
    }
}