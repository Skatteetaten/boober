package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.filterDefaultFields
import no.skatteetaten.aurora.boober.service.renderJsonForAuroraDeploymentSpecPointers
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/auroradeployspec/{auroraConfigName}/{environment}/{application}")
class AuroraDeploymentSpecControllerV1(val auroraConfigService: AuroraConfigService) {

    @GetMapping()
    fun get(
            @PathVariable auroraConfigName: String,
            @PathVariable environment: String,
            @PathVariable application: String,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        val spec = createAuroraDeploymentSpec(auroraConfig, ApplicationId.aid(environment, application))

        val filteredFields = if (includeDefaults) spec.fields else filterDefaultFields(spec.fields)

        return Response(items = listOf(filteredFields))
    }

    @GetMapping("/formatted")
    fun getJsonForMapOfPointers(
            @PathVariable auroraConfigName: String,
            @PathVariable environment: String,
            @PathVariable application: String,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        val spec = createAuroraDeploymentSpec(auroraConfig, ApplicationId.aid(environment, application))
        val formatted = renderJsonForAuroraDeploymentSpecPointers(spec, includeDefaults)
        return Response(items = listOf(formatted))
    }
}