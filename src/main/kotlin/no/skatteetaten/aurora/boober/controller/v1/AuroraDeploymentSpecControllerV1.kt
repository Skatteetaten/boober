package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.mapper.AuroraContextCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.*
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.stereotype.Component
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
        val auroraConfigService: AuroraConfigService,
        val responder: AuroraDeploymentContextResponder

) {

    // TODO: How much of the logic here should be in a seperate service?
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
        val specs = adrList.map(ApplicationDeploymentRef.Companion::fromString)
                .let { auroraDeploymentContextService.getAuroraDeploymentContexts(auroraConfig, it, ref) }
        return responder.create(
                specs,
                includeDefaults ?: true
        )
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
        return responder.create(specs, includeDefaults)
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
                applicationDeploymentRef = ApplicationDeploymentRef.adr(environment, application),
                auroraConfigRef = ref,
                overrides = overrideFiles
        )
        return responder.create(auroraDeploymentContextService.createAuroraDeploymentContext(cmd))
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

        val applicationDeploymentRef = ApplicationDeploymentRef.adr(environment, application)

        val spec = auroraDeploymentContextService.createAuroraDeploymentContext(
                AuroraContextCommand(
                        auroraConfig = auroraConfig,
                        applicationDeploymentRef = applicationDeploymentRef,
                        auroraConfigRef = ref,
                        overrides = extractOverrides(overrides)
                )
        ).spec

        val formatted = renderJsonForAuroraDeploymentSpecPointers(spec, includeDefaults)
        return responder.create(formatted)
    }


}

@Component
class AuroraDeploymentContextResponder {
    fun create(formatted: String) = Response(items = listOf(formatted))

    fun create(specInternal: AuroraDeploymentContext, includeDefaults: Boolean = true): Response =
            create(listOf(specInternal), includeDefaults)

    fun create(contexts: List<AuroraDeploymentContext>, includeDefaults: Boolean): Response {
        val fields = contexts.map { renderSpecAsJson(it.spec, includeDefaults) }
        return Response(items = fields)
    }
}
