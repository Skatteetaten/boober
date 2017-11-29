package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.internal.UpdateAuroraConfigFilePayload
import no.skatteetaten.aurora.boober.controller.internal.fromAuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.DeployBundleService
import no.skatteetaten.aurora.boober.utils.logger
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid

@RestController
@RequestMapping("/v1/auroraconfigfile/{affiliation}")
class AuroraConfigFileControllerV1(val deployBundleService: DeployBundleService) {


    val logger by logger()


    @GetMapping("/**")
    fun getAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest): Response {

        val fileName = extractFileName(affiliation, request)
        val res = deployBundleService.findAuroraConfigFile(affiliation, fileName)?.let { listOf(it) } ?: emptyList()

        return Response(items = res)
    }


    @PutMapping("/**")
    fun updateAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest,
                               @RequestBody @Valid payload: UpdateAuroraConfigFilePayload): Response {

        if (payload.validateVersions && payload.version.isEmpty()) {
            throw IllegalAccessException("Must specify version");
        }
        val fileName = extractFileName(affiliation, request)

        val auroraConfig = deployBundleService.updateAuroraConfigFile(affiliation, fileName,
                payload.contentAsJsonNode, payload.version, payload.validateVersions)
        return createAuroraConfigResponse(auroraConfig)
    }


    @PatchMapping("/**")
    fun patchAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest,
                              @RequestBody @Valid payload: UpdateAuroraConfigFilePayload): Response {

        if (payload.validateVersions && payload.version.isEmpty()) {
            throw IllegalAccessException("Must specify version");
        }

        val fileName = extractFileName(affiliation, request)

        val auroraConfig = deployBundleService.patchAuroraConfigFile(affiliation, fileName,
                payload.content, payload.version, payload.validateVersions)
        return createAuroraConfigResponse(auroraConfig)
    }

    private fun createAuroraConfigResponse(auroraConfig: AuroraConfig): Response {
        return Response(items = listOf(auroraConfig).map(::fromAuroraConfig))
    }

    private fun extractFileName(affiliation: String, request: HttpServletRequest): String {
        val path = "v1/auroraconfigfile/$affiliation/**"
        return AntPathMatcher().extractPathWithinPattern(path, request.requestURI)
    }
}