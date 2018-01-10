package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.mapper.v1.createAuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.filterDefaultFields
import no.skatteetaten.aurora.boober.service.renderJsonForAuroraDeploymentSpecPointers
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/auroradeployspec/{auroraConfigName}")
class AuroraDeploymentSpecControllerV1(val auroraConfigService: AuroraConfigService) {

    @GetMapping("/")
    fun findAllDeploymentSpecs(
            @PathVariable auroraConfigName: String,
            @RequestParam(name = "aid", required = false) aidStrings: List<String>,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val aids = aidStrings.map(ApplicationId.Companion::fromString)
        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)

        val fields = if (aids.isEmpty()) {
            auroraConfig.getAllAuroraDeploymentSpecs()
        } else {
            aids.map { auroraConfig.getAuroraDeploymentSpec(it) }
        }.map {
            if (includeDefaults) it.fields else filterDefaultFields(it.fields)
        }

        return Response(items = fields)
    }

    @GetMapping("/{environment}/")
    fun findAllDeploymentSpecsForEnvironment(
            @PathVariable auroraConfigName: String,
            @PathVariable environment: String,
            @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val auroraConfig = auroraConfigService.findAuroraConfig(auroraConfigName)
        val fields = auroraConfig.getApplicationIds()
                .filter { it.environment == environment }
                .map { auroraConfig.getAuroraDeploymentSpec(it) }
                .map { if (includeDefaults) it.fields else filterDefaultFields(it.fields) }

        return Response(items = fields)
    }

    @GetMapping("/{environment}/{application}")
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

    @GetMapping("/{environment}/{application}/formatted")
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