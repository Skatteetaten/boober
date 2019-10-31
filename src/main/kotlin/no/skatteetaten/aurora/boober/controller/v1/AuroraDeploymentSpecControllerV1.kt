package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.charset.Charset
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.toAdr
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.renderJsonForAuroraDeploymentSpecPointers
import no.skatteetaten.aurora.boober.service.renderSpecAsJson
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriUtils

@RestController
@RequestMapping("/v1/auroradeployspec/{auroraConfigName}")
class AuroraDeploymentSpecControllerV1(
    val facade: AuroraConfigFacade
) {

    @GetMapping
    fun findAllDeploymentSpecs(
        @PathVariable auroraConfigName: String,
        @RequestParam aid: List<String>?,
        @RequestParam adr: List<String>?,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val adrList = listOf<String>().addIfNotNull(aid).addIfNotNull(adr).map { it.toAdr() }
        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val contexts = facade.findAuroraDeploymentSpec(ref, adrList)

        return Response(items = contexts.map {
            renderSpecAsJson(it, includeDefaults)
        })
    }

    @GetMapping("/{environment}/")
    fun findAllDeploymentSpecsForEnvironment(
        @PathVariable auroraConfigName: String,
        @PathVariable environment: String,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val specs = facade.findAuroraDeploymentSpecForEnvironment(ref, environment)
        return Response(items = specs.map { renderSpecAsJson(it, includeDefaults) })
    }

    @GetMapping("/{environment}/{application}")
    fun get(
        @PathVariable auroraConfigName: String,
        @PathVariable environment: String,
        @PathVariable application: String,
        @RequestParam(name = "overrides", required = false) overrides: String?,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val overrideFiles: List<AuroraConfigFile> = extractOverrides(overrides)
        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val adr = ApplicationDeploymentRef(environment, application)
        val spec = facade.findAuroraDeploymentSpecSingle(ref, adr, overrideFiles)
        return Response(items = listOf(spec).map { renderSpecAsJson(it, includeDefaults) })
    }

    @GetMapping("/{environment}/{application}/formatted")
    fun getJsonForMapOfPointers(
        @PathVariable auroraConfigName: String,
        @PathVariable environment: String,
        @PathVariable application: String,
        @RequestParam(name = "overrides", required = false) overrides: String?,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val adr = ApplicationDeploymentRef(environment, application)
        val overrideFiles = extractOverrides(overrides)
        val spec = facade.findAuroraDeploymentSpecSingle(ref, adr, overrideFiles)
        return Response(items = listOf(renderJsonForAuroraDeploymentSpecPointers(spec, includeDefaults)))
    }

    fun extractOverrides(overrides: String?): List<AuroraConfigFile> {
        if (overrides.isNullOrBlank()) {
            return emptyList()
        }
        val files: Map<String, String> = jacksonObjectMapper()
            .readValue(UriUtils.decode(overrides, Charset.defaultCharset().toString()))

        return files.map { AuroraConfigFile(it.key, it.value, true) }
    }
}
