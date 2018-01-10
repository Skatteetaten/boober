package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.annotation.JsonRawValue
import no.skatteetaten.aurora.boober.controller.internal.JsonDataFiles
import no.skatteetaten.aurora.boober.controller.internal.Response
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
        val files: JsonDataFiles = mapOf(),
        val versions: Map<String, String?> = mapOf()
) {
    fun toAuroraConfig(affiliation: String): AuroraConfig {
        val auroraConfigFiles = files.map { AuroraConfigFile(it.key, it.value) }
        return AuroraConfig(auroraConfigFiles, affiliation)
    }
}


data class UpdateAuroraConfigFilePayload(
        val version: String = "",
        val validateVersions: Boolean = true,

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
        val configFiles = auroraConfigService.findAuroraConfigFile(name, fileName)
                ?.let { listOf(it) } ?: emptyList()

        return Response(items = configFiles)
    }


    @PutMapping("/**")
    fun updateAuroraConfigFile(@PathVariable name: String, request: HttpServletRequest,
                               @RequestBody @Valid payload: UpdateAuroraConfigFilePayload): Response {

        if (payload.validateVersions && payload.version.isEmpty()) {
            throw IllegalAccessException("Must specify version");
        }
        val fileName = extractFileName(name, request)

        val auroraConfig = auroraConfigService.updateAuroraConfigFile(name, fileName, payload.content, payload.version)
        return createAuroraConfigResponse(auroraConfig)
    }


    @PatchMapping("/**")
    fun patchAuroraConfigFile(@PathVariable name: String, request: HttpServletRequest,
                              @RequestBody @Valid payload: UpdateAuroraConfigFilePayload): Response {

        if (payload.validateVersions && payload.version.isEmpty()) {
            throw IllegalAccessException("Must specify version");
        }

        val fileName = extractFileName(name, request)

        val auroraConfig = auroraConfigService.patchAuroraConfigFile(name, fileName,
                payload.content, payload.version)
        return createAuroraConfigResponse(auroraConfig)
    }

    private fun extractFileName(affiliation: String, request: HttpServletRequest): String {
        val path = "v1/auroraconfig/$affiliation/**"
        return AntPathMatcher().extractPathWithinPattern(path, request.requestURI)
    }

    private fun createAuroraConfigResponse(auroraConfig: AuroraConfig): Response {
        return Response(items = listOf(auroraConfig).map { fromAuroraConfig(it) })
    }


    fun fromAuroraConfig(auroraConfig: AuroraConfig): AuroraConfigResource {

        val files: JsonDataFiles = auroraConfig.auroraConfigFiles.associate { it.name to it.contents }
        val versions = auroraConfig.auroraConfigFiles.associate { it.name to it.version }
        return AuroraConfigResource(auroraConfig.affiliation, files, versions = versions)
    }
}