package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.internal.UpdateAuroraConfigFilePayload
import no.skatteetaten.aurora.boober.controller.internal.fromAuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.DeployBundleService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid

@RestController
@RequestMapping("/v1/auroraconfigfile/{affiliation}")
class AuroraConfigFileController(val deployBundleService: DeployBundleService) {


    val logger: Logger = LoggerFactory.getLogger(AuroraConfigFileController::class.java)


    @GetMapping("/**")
    fun getAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest): Response {

        val path = "affiliation/$affiliation/auroraconfig/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

        val res = deployBundleService.findAuroraConfigFile(affiliation, fileName)?.let { listOf(it) } ?: emptyList()

        return Response(items = res)
    }


    @PutMapping("/**")
    fun updateAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest,
                               @RequestBody @Valid payload: UpdateAuroraConfigFilePayload): Response {

        if (payload.validateVersions && payload.version.isEmpty()) {
            throw IllegalAccessException("Must specify version");
        }
        val path = "affiliation/$affiliation/auroraconfigfile/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

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

        val path = "affiliation/$affiliation/auroraconfigfile/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

        val auroraConfig = deployBundleService.patchAuroraConfigFile(affiliation, fileName,
                payload.content, payload.version, payload.validateVersions)
        return createAuroraConfigResponse(auroraConfig)
    }

    private fun createAuroraConfigResponse(auroraConfig: AuroraConfig): Response {
        return Response(items = listOf(auroraConfig).map(::fromAuroraConfig))
    }
}