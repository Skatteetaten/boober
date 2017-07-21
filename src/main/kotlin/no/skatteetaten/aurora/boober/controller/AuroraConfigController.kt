package no.skatteetaten.aurora.boober.controller

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.internal.AuroraConfigPayload
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.internal.fromAuroraConfig
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
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
class AuroraConfigController(val auroraConfigFacade: AuroraConfigFacade) {

    @PutMapping("/auroraconfig")
    fun save(@PathVariable affiliation: String,
             @RequestBody payload: AuroraConfigPayload,
             @RequestHeader(value = "AuroraValidateVersions") validateVersions: Boolean = true): Response {

        val auroraConfig = auroraConfigFacade.saveAuroraConfig(affiliation, payload.toAuroraConfig(affiliation), validateVersions)
        return Response(items = listOf(auroraConfig).map(::fromAuroraConfig))
    }

    @GetMapping("/auroraconfig")
    fun get(@PathVariable affiliation: String): Response {
        return Response(items = listOf(auroraConfigFacade.findAuroraConfig(affiliation)).map(::fromAuroraConfig))
    }


    @PutMapping("/auroraconfigfile/**")
    fun updateAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest,
                               @RequestBody fileContents: JsonNode,
                               @RequestHeader(value = "AuroraConfigFileVersion") configFileVersion: String,
                               @RequestHeader(value = "AuroraValidateVersions") validateVersions: Boolean = true): Response {

        val path = "affiliation/$affiliation/auroraconfigfile/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

        val auroraConfig = auroraConfigFacade.updateAuroraConfigFile(affiliation, fileName, fileContents, configFileVersion, validateVersions)
        return Response(items = listOf(auroraConfig).map(::fromAuroraConfig))
    }

    @PatchMapping(value = "/auroraconfigfile/**", consumes = arrayOf("application/json-patch+json"))
    fun patchAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest,
                              @RequestBody jsonPatchOp: String,
                              @RequestHeader(value = "AuroraConfigFileVersion") configFileVersion: String,
                              @RequestHeader(value = "AuroraValidateVersions") validateVersions: Boolean = true): Response {

        val path = "affiliation/$affiliation/auroraconfigfile/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

        val auroraConfig = auroraConfigFacade.patchAuroraConfigFile(affiliation, fileName, jsonPatchOp, configFileVersion, validateVersions)
        return Response(items = listOf(auroraConfig).map(::fromAuroraConfig))
    }
}


