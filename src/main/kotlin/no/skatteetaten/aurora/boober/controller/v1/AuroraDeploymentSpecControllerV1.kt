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
    ): Response = response(auroraDeploymentSpecService.getAuroraDeploymentSpecs(auroraConfigName, aidStrings), includeDefaults)

    @GetMapping("/{environment}/")
    fun findAllDeploymentSpecsForEnvironment(
            @PathVariable auroraConfigName: String,
            @PathVariable environment: String,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response = response(auroraDeploymentSpecService.getAuroraDeploymentSpecsForEnvironment(auroraConfigName, environment), includeDefaults)

    @GetMapping("/{environment}/{application}")
    fun get(
            @PathVariable auroraConfigName: String,
            @PathVariable environment: String,
            @PathVariable application: String,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response = response(auroraDeploymentSpecService.getAuroraDeploymentSpec(auroraConfigName, environment, application), includeDefaults)

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

    private fun response(spec: AuroraDeploymentSpec, includeDefaults: Boolean): Response = response(listOf(spec), includeDefaults)

    private fun response(spec: List<AuroraDeploymentSpec>, includeDefaults: Boolean): Response {

        val fields = spec.map { it.fields }
        return Response(items = if (includeDefaults) fields else fields.map(::filterDefaultFields))
    }
}