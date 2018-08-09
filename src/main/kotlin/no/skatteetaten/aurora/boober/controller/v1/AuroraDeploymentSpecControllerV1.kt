package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.mapper.present
import no.skatteetaten.aurora.boober.mapper.removeDefaults
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
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

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        return response(auroraDeploymentSpecService.getAuroraDeploymentSpecs(ref, aidStrings), includeDefaults)
    }

    @GetMapping("/{environment}/")
    fun findAllDeploymentSpecsForEnvironment(
        @PathVariable auroraConfigName: String,
        @PathVariable environment: String,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        return response(
            auroraDeploymentSpecService.getAuroraDeploymentSpecsForEnvironment(ref, environment),
            includeDefaults
        )
    }

    @GetMapping("/{environment}/{application}")
    fun get(
        @PathVariable auroraConfigName: String,
        @PathVariable environment: String,
        @PathVariable application: String,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        return response(
            auroraDeploymentSpecService.getAuroraDeploymentSpec(ref, environment, application),
            includeDefaults
        )
    }

    @GetMapping("/{environment}/{application}/formatted")
    fun getJsonForMapOfPointers(
        @PathVariable auroraConfigName: String,
        @PathVariable environment: String,
        @PathVariable application: String,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val spec = auroraDeploymentSpecService.getAuroraDeploymentSpec(ref, environment, application)
        val formatted = renderJsonForAuroraDeploymentSpecPointers(spec, includeDefaults)
        return Response(items = listOf(formatted))
    }

    private fun response(spec: AuroraDeploymentSpec, includeDefaults: Boolean): Response =
        response(listOf(spec), includeDefaults)

    private fun response(spec: List<AuroraDeploymentSpec>, includeDefaults: Boolean): Response {

        val fields = spec.map {
            val rawFields = if (!includeDefaults) {
                it.fields.removeDefaults()
            } else {
                it.fields
            }

            rawFields.present {
                mapOf(
                    "source" to it.value.source,
                    "value" to it.value.value,
                    "sources" to it.value.sources
                )
            }
        }
        return Response(items = fields)
    }
}
