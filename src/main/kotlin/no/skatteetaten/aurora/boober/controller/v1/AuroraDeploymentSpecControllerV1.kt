package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.mapper.present
import no.skatteetaten.aurora.boober.mapper.removeDefaults
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
import no.skatteetaten.aurora.boober.service.renderJsonForAuroraDeploymentSpecPointers
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriUtils
import java.nio.charset.Charset

@RestController
@RequestMapping("/v1/auroradeployspec/{auroraConfigName}")
class AuroraDeploymentSpecControllerV1(val auroraDeploymentSpecService: AuroraDeploymentSpecService) {

    @GetMapping("/")
    fun findAllDeploymentSpecs(
        @PathVariable auroraConfigName: String,
        @RequestParam(name = "aid", required = false) aidStrings: List<String>,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean,
        @RequestParam(name = "reference", required = false) reference: String?
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest(reference))
        return response(auroraDeploymentSpecService.getAuroraDeploymentSpecs(ref, aidStrings), includeDefaults)
    }

    @GetMapping("/{environment}/")
    fun findAllDeploymentSpecsForEnvironment(
        @PathVariable auroraConfigName: String,
        @PathVariable environment: String,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean,
        @RequestParam(name = "reference", required = false) reference: String?
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest(reference))
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
        @RequestParam(name = "overrides", required = false) overrides: String?,
        @RequestParam(name = "reference", required = false) reference: String?,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val overrideFiles: List<AuroraConfigFile> = extractOverrides(overrides)

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest(reference))
        return response(
            auroraDeploymentSpecService.getAuroraDeploymentSpec(
                ref = ref,
                environment = environment,
                application = application,
                overrides = overrideFiles
            ), true
        )
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
        @RequestParam(name = "reference", required = false) reference: String?,
        @RequestParam(name = "includeDefaults", required = false, defaultValue = "true") includeDefaults: Boolean
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest(reference))
        val spec = auroraDeploymentSpecService.getAuroraDeploymentSpec(
            ref = ref,
            environment = environment,
            application = application,
            overrides = extractOverrides(overrides)
        )
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
