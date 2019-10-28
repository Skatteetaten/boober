package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.toAdr
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.AuroraDeploymentContextService
import no.skatteetaten.aurora.boober.service.renderJsonForAuroraDeploymentSpecPointers
import no.skatteetaten.aurora.boober.service.renderSpecAsJson
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriUtils
import java.nio.charset.Charset

@RestController
@RequestMapping("/v1/auroradeployspec/{auroraConfigName}")
class AuroraDeploymentSpecControllerV1(
    val auroraDeploymentContextService: AuroraDeploymentContextService,
    val auroraConfigService: AuroraConfigService
) {

    @GetMapping
    fun findAllDeploymentSpecs(
        @PathVariable auroraConfigName: String,
        @RequestParam aid: List<String>?,
        @RequestParam adr: List<String>?,
        @RequestParam includeDefaults: Boolean?
    ): Response {

        val adrList = listOf<String>().addIfNotNull(aid).addIfNotNull(adr)

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        val specs = adrList.map { it.toAdr() }
            .let { auroraDeploymentContextService.getAuroraDeploymentContexts(auroraConfig, it, ref) }
        return Response(items = specs.map {
            renderSpecAsJson(
                it.spec, includeDefaults ?: true
            )
        })
    }

    @GetMapping("/{environment}/")
    fun findAllDeploymentSpecsForEnvironment(
        @PathVariable auroraConfigName: String,
        @PathVariable environment: String,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        val specs = auroraConfig.getApplicationDeploymentRefs()
            .filter { it.environment == environment }
            .let { auroraDeploymentContextService.getAuroraDeploymentContexts(auroraConfig, it, ref) }
        return Response(items = specs.map { renderSpecAsJson(it.spec, includeDefaults) })
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
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)
        val cmd = AuroraContextCommand(
            auroraConfig = auroraConfig,
            applicationDeploymentRef = ApplicationDeploymentRef(environment, application),
            auroraConfigRef = ref,
            overrides = overrideFiles
        )
        return Response(items = listOf(auroraDeploymentContextService.createAuroraDeploymentContext(cmd)).map {
            renderSpecAsJson(
                it.spec,
                true
            )
        })
    }

    fun extractOverrides(overrides: String?): List<AuroraConfigFile> {
        if (overrides.isNullOrBlank()) {
            return emptyList()
        }
        val files: Map<String, String> = jacksonObjectMapper()
            .readValue(UriUtils.decode(overrides, Charset.defaultCharset().toString()))

        return files.map { AuroraConfigFile(it.key, it.value, true) }
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
        val auroraConfig = auroraConfigService.findAuroraConfig(ref)

        val applicationDeploymentRef = ApplicationDeploymentRef(environment, application)

        val spec = auroraDeploymentContextService.createAuroraDeploymentContext(
            AuroraContextCommand(
                auroraConfig = auroraConfig,
                applicationDeploymentRef = applicationDeploymentRef,
                auroraConfigRef = ref,
                overrides = extractOverrides(overrides)
            )
        ).spec

        return Response(items = listOf(renderJsonForAuroraDeploymentSpecPointers(spec, includeDefaults)))
    }
}
