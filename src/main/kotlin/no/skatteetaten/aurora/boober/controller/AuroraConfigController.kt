package no.skatteetaten.aurora.boober.controller

import io.micrometer.core.annotation.Timed
import no.skatteetaten.aurora.boober.controller.internal.AuroraConfigPayload
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.internal.UpdateAuroraConfigFilePayload
import no.skatteetaten.aurora.boober.controller.internal.fromAuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.DeployBundleService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest
import javax.validation.Valid


@RestController
@RequestMapping("/affiliation/{affiliation}")
class AuroraConfigController(val deployBundleService: DeployBundleService) {


    val logger: Logger = LoggerFactory.getLogger(AuroraConfigController::class.java)

    @Timed
    @GetMapping("/auroraconfig/filenames")
    fun getFilenames(@PathVariable affiliation: String): Response {
        logger.info("Henter aurora config filenames affiliation={}", affiliation)
        val res = Response(items = deployBundleService.findAuroraConfigFileNames(affiliation))
        logger.debug("/Henter aurora config filenames")
        return res
    }

    @Timed
    @GetMapping("/auroraconfigfile/**")
    fun getAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest): Response {

        val path = "affiliation/$affiliation/auroraconfig/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

        val res = deployBundleService.findAuroraConfigFile(affiliation, fileName)?.let { listOf(it) } ?: emptyList()

        return Response(items = res)
    }

    @Timed
    @PutMapping("/auroraconfig")
    fun save(@PathVariable affiliation: String,
             @RequestBody payload: AuroraConfigPayload): Response {

        logger.info("Save aurora config affilation={}", affiliation)
        val auroraConfig = deployBundleService.saveAuroraConfig(payload.toAuroraConfig(affiliation), payload.validateVersions)
        val res = createAuroraConfigResponse(auroraConfig)

        logger.debug("/Save aurora config")
        return res
    }

    @Timed
    @PutMapping("/auroraconfig/validate")
    fun validateAuroraConfig(@PathVariable affiliation: String, @RequestBody payload: AuroraConfigPayload): Response {

        val auroraConfig = deployBundleService.validateDeployBundleWithAuroraConfig(affiliation, payload.toAuroraConfig(affiliation))
        return createAuroraConfigResponse(auroraConfig)
    }

    @Timed
    @GetMapping("/auroraconfig")
    fun get(@PathVariable affiliation: String): Response {
        logger.info("Henter aurora config affiliation={}", affiliation)
        val res = createAuroraConfigResponse(deployBundleService.findAuroraConfig(affiliation))
        logger.debug("/Henter aurora config")
        return res
    }


    @Timed
    @PutMapping("/auroraconfigfile/**")
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


    @Timed
    @PatchMapping(value = "/auroraconfigfile/**")
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


