package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.annotation.JsonRawValue
import no.skatteetaten.aurora.boober.controller.NoSuchResourceException
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigResource.Companion.fromAuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.utils.logger
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid

data class AuroraConfigResource(
    val name: String,
    val files: List<AuroraConfigFileResource> = listOf()
) {
    fun toAuroraConfig(ref: AuroraConfigRef): AuroraConfig {
        val auroraConfigFiles = files.map { AuroraConfigFile(it.name, it.contents) }
        return AuroraConfig(auroraConfigFiles, ref.name, ref.refName)
    }

    companion object {
        fun fromAuroraConfig(auroraConfig: AuroraConfig): AuroraConfigResource {
            return AuroraConfigResource(auroraConfig.name, auroraConfig.files.map { AuroraConfigFileResource(it.name, it.contents) })
        }
    }
}

data class AuroraConfigFileResource(
    val name: String,
    val contents: String
)

data class ContentPayload(
    @JsonRawValue
    val content: String
)

@RestController
@RequestMapping("/v1/auroraconfig/{name}")
class AuroraConfigControllerV1(val auroraConfigService: AuroraConfigService) {

    val logger by logger()

    @GetMapping()
    fun get(@PathVariable name: String): Response {
        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        return createAuroraConfigResponse(auroraConfigService.findAuroraConfig(ref))
    }

    @GetMapping("/filenames")
    fun getFilenames(
        @PathVariable name: String
    ): Response {
        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        return Response(items = auroraConfigService.findAuroraConfigFileNames(ref))
    }

    @PutMapping("/validate")
    fun validateAuroraConfig(
        @PathVariable name: String,
        @RequestParam("resourceValidation", required = false, defaultValue = "false") resourceValidation: Boolean,
        @RequestBody payload: AuroraConfigResource
    ): Response {

        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val auroraConfig = payload.toAuroraConfig(ref)
        auroraConfigService.validateAuroraConfig(auroraConfig, resourceValidation = resourceValidation)
        return createAuroraConfigResponse(auroraConfig)
    }

    @GetMapping("/**")
    fun getAuroraConfigFile(@PathVariable name: String, request: HttpServletRequest): ResponseEntity<Response> {

        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val fileName = extractFileName(name, request)
        val auroraConfigFile = auroraConfigService.findAuroraConfigFile(ref, fileName)
            ?: throw NoSuchResourceException("No such file $fileName in AuroraConfig $name")
        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    @PutMapping("/**")
    fun updateAuroraConfigFile(
        @PathVariable name: String,
        @RequestBody @Valid payload: ContentPayload,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) ifMatchHeader: String?,
        request: HttpServletRequest
    ): ResponseEntity<Response> {

        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val fileName = extractFileName(name, request)
        val auroraConfig: AuroraConfig = auroraConfigService.updateAuroraConfigFile(ref, fileName, payload.content, clearQuotes(ifMatchHeader))
        val auroraConfigFile = auroraConfig.findFile(fileName)!!
        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    @PatchMapping("/**")
    fun patchAuroraConfigFile(
        @PathVariable name: String,
        request: HttpServletRequest,
        @RequestBody @Valid payload: ContentPayload
    ): ResponseEntity<Response> {

        val ref = AuroraConfigRef(name, getRefNameFromRequest())
        val fileName = extractFileName(name, request)

        val auroraConfig = auroraConfigService.patchAuroraConfigFile(ref, fileName, payload.content)
        val auroraConfigFile = auroraConfig.findFile(fileName)!!
        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    private fun extractFileName(affiliation: String, request: HttpServletRequest): String {
        val path = "v1/auroraconfig/$affiliation/**"
        return AntPathMatcher().extractPathWithinPattern(path, request.requestURI)
    }

    private fun createAuroraConfigFileResponse(auroraConfigFile: AuroraConfigFile): ResponseEntity<Response> {
        val configFiles = auroraConfigFile
            .let { listOf(AuroraConfigFileResource(it.name, it.contents)) }

        val response = Response(items = configFiles)
        val headers = HttpHeaders().apply { eTag = "\"${auroraConfigFile.version}\"" }
        return ResponseEntity(response, headers, HttpStatus.OK)
    }

    private fun createAuroraConfigResponse(auroraConfig: AuroraConfig): Response {
        return Response(items = listOf(auroraConfig).map { fromAuroraConfig(it) })
    }
}