package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.AuroraConfigPayload
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.internal.fromAuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.DeployBundleService
import no.skatteetaten.aurora.boober.utils.logger
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/auroraconfig/{affiliation}")
class AuroraConfigControllerV1(val deployBundleService: DeployBundleService) {

    val logger by logger()

    @GetMapping("/filenames")
    fun getFilenames(@PathVariable affiliation: String): Response {
        logger.info("Henter aurora config filenames affiliation={}", affiliation)
        val res = Response(items = deployBundleService.findAuroraConfigFileNames(affiliation))
        logger.debug("/Henter aurora config filenames")
        return res
    }


    @PutMapping()
    fun save(@PathVariable affiliation: String,
             @RequestBody payload: AuroraConfigPayload): Response {

        logger.info("Save aurora config affilation={}", affiliation)
        val auroraConfig = deployBundleService.saveAuroraConfig(payload.toAuroraConfig(affiliation), payload.validateVersions)
        val res = createAuroraConfigResponse(auroraConfig)

        logger.debug("/Save aurora config")
        return res
    }

    @PutMapping("/validate")
    fun validateAuroraConfig(@PathVariable affiliation: String, @RequestBody payload: AuroraConfigPayload): Response {

        val auroraConfig = deployBundleService.validateDeployBundleWithAuroraConfig(affiliation, payload.toAuroraConfig(affiliation))
        return createAuroraConfigResponse(auroraConfig)
    }

    @GetMapping()
    fun get(@PathVariable affiliation: String): Response {
        logger.info("Henter aurora config affiliation={}", affiliation)
        val res = createAuroraConfigResponse(deployBundleService.findAuroraConfig(affiliation))
        logger.debug("/Henter aurora config")
        return res
    }


    private fun createAuroraConfigResponse(auroraConfig: AuroraConfig): Response {
        return Response(items = listOf(auroraConfig).map(::fromAuroraConfig))
    }
}


