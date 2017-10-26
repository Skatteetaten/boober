package no.skatteetaten.aurora.boober.controller

import com.fasterxml.jackson.databind.JsonNode
import io.micrometer.core.annotation.Timed
import no.skatteetaten.aurora.boober.controller.internal.AuroraConfigPayload
import no.skatteetaten.aurora.boober.controller.internal.Response
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
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletRequest

@RestController
@RequestMapping("/affiliation/{affiliation}")
class AuroraConfigController(val deployBundleService: DeployBundleService) {


    val logger: Logger = LoggerFactory.getLogger(AuroraConfigController::class.java)
    //TODO: Method to return a single auroraConfig File
    //TODO: Method to return all filenames

    @Timed
    @PutMapping("/auroraconfig")
    fun save(@PathVariable affiliation: String,
             @RequestBody payload: AuroraConfigPayload,
             @RequestHeader(value = "AuroraValidateVersions", required = false) validateVersions: Boolean = true): Response {

        val auroraConfig = deployBundleService.saveAuroraConfig(payload.toAuroraConfig(affiliation), validateVersions)
        return createAuroraConfigResponse(auroraConfig)
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
        logger.debug("Henter aurora config")
        val res =  createAuroraConfigResponse(deployBundleService.findAuroraConfig(affiliation))
        logger.debug("/Henter aurora config")
        return res
    }


    @Timed
    @PutMapping("/auroraconfigfile/**")
    fun updateAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest,
                               @RequestBody fileContents: JsonNode,
                               @RequestHeader(value = "AuroraConfigFileVersion") configFileVersion: String = "",
                               @RequestHeader(value = "AuroraValidateVersions", required = false) validateVersions: Boolean = true): Response {

        if(validateVersions && configFileVersion.isEmpty()) {
            throw IllegalAccessException("Must specify AuroraConfigFileVersion header");
        }
        val path = "affiliation/$affiliation/auroraconfigfile/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

        val auroraConfig = deployBundleService.updateAuroraConfigFile(affiliation, fileName, fileContents, configFileVersion, validateVersions)
        return createAuroraConfigResponse(auroraConfig)
    }


    @Timed
    @PatchMapping(value = "/auroraconfigfile/**", consumes = arrayOf("application/json-patch+json"))
    fun patchAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest,
                              @RequestBody jsonPatchOp: String,
                              @RequestHeader(value = "AuroraConfigFileVersion") configFileVersion: String = "",
                              @RequestHeader(value = "AuroraValidateVersions", required = false) validateVersions: Boolean = true): Response {

        if(validateVersions && configFileVersion.isEmpty()) {
            throw IllegalAccessException("Must specify AuroraConfigFileVersion header");
        }

        val path = "affiliation/$affiliation/auroraconfigfile/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

        val auroraConfig = deployBundleService.patchAuroraConfigFile(affiliation, fileName, jsonPatchOp, configFileVersion, validateVersions)
        return createAuroraConfigResponse(auroraConfig)
    }

    private fun createAuroraConfigResponse(auroraConfig: AuroraConfig): Response {
        return Response(items = listOf(auroraConfig).map(::fromAuroraConfig))
    }
}


