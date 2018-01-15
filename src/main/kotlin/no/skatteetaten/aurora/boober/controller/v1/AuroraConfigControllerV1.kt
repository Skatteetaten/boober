package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.annotation.JsonRawValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigResource.Companion.fromAuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.DeploymentSpecService
import no.skatteetaten.aurora.boober.utils.logger
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
class AuroraConfigControllerV1(val auroraConfigService: AuroraConfigService, val deploymentSpecService: DeploymentSpecService) {

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
        deploymentSpecService.validateAuroraConfig(auroraConfig)
        return createAuroraConfigResponse(auroraConfig)
    }

    @GetMapping("/**")
    fun getAuroraConfigFile(@PathVariable name: String, request: HttpServletRequest): Response {

        val fileName = extractFileName(name, request)
        val auroraConfigFile = auroraConfigService.findAuroraConfigFile(name, fileName)
        return createAuroraConfigFileResponse(auroraConfigFile)
    }

    @PutMapping("/**")
    fun updateAuroraConfigFile(@PathVariable name: String, request: HttpServletRequest,
                               @RequestBody @Valid payload: ContentPayload): Response {

        val fileName = extractFileName(name, request)
        val auroraConfig = auroraConfigService.updateAuroraConfigFile(name, fileName, payload.content)
        return createAuroraConfigResponse(auroraConfig)
    }

    @PatchMapping("/**")
    fun patchAuroraConfigFile(@PathVariable name: String, request: HttpServletRequest,
                              @RequestBody @Valid payload: ContentPayload): Response {

        val fileName = extractFileName(name, request)

        val auroraConfig = auroraConfigService.patchAuroraConfigFile(name, fileName, payload.content)
        return createAuroraConfigResponse(auroraConfig)
    }

    private fun extractFileName(affiliation: String, request: HttpServletRequest): String {
        val path = "v1/auroraconfig/$affiliation/**"
        return AntPathMatcher().extractPathWithinPattern(path, request.requestURI)
    }

    private fun createAuroraConfigFileResponse(auroraConfigFile: AuroraConfigFile?): Response {
        val configFiles = auroraConfigFile
                ?.let { listOf(AuroraConfigFileResource(it.name, it.contents.toString())) } ?: emptyList()

        return Response(items = configFiles)
    }

    private fun createAuroraConfigResponse(auroraConfig: AuroraConfig): Response {
        return Response(items = listOf(auroraConfig).map { fromAuroraConfig(it) })
    }
}