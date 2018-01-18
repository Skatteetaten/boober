package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.annotation.JsonRawValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.controller.NoSuchResourceException
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigResource.Companion.fromAuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.utils.logger
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid

data class AuroraConfigResource(
        val name: String,
        val files: List<AuroraConfigFileResource> = listOf()
) {
    fun toAuroraConfig(affiliation: String): AuroraConfig {
        val auroraConfigFiles = files.map { AuroraConfigFile(it.name, jacksonObjectMapper().readTree(it.contents)) }
        return AuroraConfig(auroraConfigFiles, affiliation)
    }

    companion object {
        fun fromAuroraConfig(auroraConfig: AuroraConfig): AuroraConfigResource {
            return AuroraConfigResource(auroraConfig.affiliation, auroraConfig.auroraConfigFiles.map { AuroraConfigFileResource(it.name, it.contents.toString()) })
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
        return createAuroraConfigResponse(auroraConfigService.findAuroraConfig(name))
    }

    @GetMapping("/filenames")
    fun getFilenames(@PathVariable name: String): Response {
        return Response(items = auroraConfigService.findAuroraConfigFileNames(name))
    }

    @PutMapping("/validate")
    fun validateAuroraConfig(@PathVariable name: String, @RequestBody payload: AuroraConfigResource): Response {

        val auroraConfig = payload.toAuroraConfig(name)
        auroraConfigService.validateAuroraConfig(auroraConfig)
        return createAuroraConfigResponse(auroraConfig)
    }

    @GetMapping("/**")
    fun getAuroraConfigFile(@PathVariable name: String, request: HttpServletRequest): ResponseEntity<Response> {

        val fileName = extractFileName(name, request)
        val auroraConfigFile = auroraConfigService.findAuroraConfigFile(name, fileName)
                ?: throw NoSuchResourceException("No such file $fileName in AuroraConfig $name")
        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    @PutMapping("/**")
    fun updateAuroraConfigFile(@PathVariable name: String,
                               @RequestBody @Valid payload: ContentPayload,
                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) ifMatchHeader: String?,
                               request: HttpServletRequest): ResponseEntity<Response> {

        val fileName = extractFileName(name, request)
        val auroraConfig: AuroraConfig = auroraConfigService.updateAuroraConfigFile(name, fileName, payload.content, clearQuotes(ifMatchHeader))
        val auroraConfigFile = auroraConfig.findFile(fileName)!!
        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    @PostMapping("/**")
    fun saveAuroraConfigFile(@PathVariable name: String,
                               @RequestBody @Valid payload: ContentPayload,
                               request: HttpServletRequest): ResponseEntity<Response> {

        val fileName = extractFileName(name, request)
        val auroraConfig: AuroraConfig = auroraConfigService.updateAuroraConfigFile(name, fileName, payload.content, null)
        val auroraConfigFile = auroraConfig.findFile(fileName)!!
        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    @PatchMapping("/**")
    fun patchAuroraConfigFile(@PathVariable name: String, request: HttpServletRequest,
                              @RequestBody @Valid payload: ContentPayload): ResponseEntity<Response> {

        val fileName = extractFileName(name, request)

        val auroraConfig = auroraConfigService.patchAuroraConfigFile(name, fileName, payload.content)
        val auroraConfigFile = auroraConfig.findFile(fileName)!!
        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    private fun extractFileName(affiliation: String, request: HttpServletRequest): String {
        val path = "v1/auroraconfig/$affiliation/**"
        return AntPathMatcher().extractPathWithinPattern(path, request.requestURI)
    }

    private fun createAuroraConfigFileResponse(auroraConfigFile: AuroraConfigFile): ResponseEntity<Response> {
        val configFiles = auroraConfigFile
                .let { listOf(AuroraConfigFileResource(it.name, it.contents.toString())) }

        val response = Response(items = configFiles)
        val headers = HttpHeaders().apply { eTag = "\"${auroraConfigFile.version}\"" }
        return ResponseEntity(response, headers, HttpStatus.OK)
    }

    private fun createAuroraConfigResponse(auroraConfig: AuroraConfig): Response {
        return Response(items = listOf(auroraConfig).map { fromAuroraConfig(it) })
    }
}