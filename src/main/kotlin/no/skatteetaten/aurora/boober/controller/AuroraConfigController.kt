package no.skatteetaten.aurora.boober.controller

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.controller.internal.AuroraConfigPayload
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.internal.fromAuroraConfig
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest

@RestController
@RequestMapping("/affiliation")
class AuroraConfigController(val auroraConfigService: AuroraConfigService) {

    @PutMapping("/{affiliation}/auroraconfig")
    fun save(@PathVariable affiliation: String, @RequestBody payload: AuroraConfigPayload): Response {

        val auroraConfig = auroraConfigService.saveAuroraConfig(affiliation, payload.toAuroraConfig())
        return Response(items = listOf(auroraConfig).map(::fromAuroraConfig))
    }

    @GetMapping("/{affiliation}/auroraconfig")
    fun get(@PathVariable affiliation: String): Response {

        return Response(items = listOf(auroraConfigService.findAuroraConfig(affiliation)).map(::fromAuroraConfig))
    }

    @PutMapping("/{affiliation}/auroraconfig/{environment}/{filename:\\w+}.json")
    fun updateAuroraConfigFile(@PathVariable affiliation: String, @PathVariable environment: String,
                               @PathVariable filename: String, @RequestBody fileContents: JsonNode): Response {

        val auroraConfig = auroraConfigService.updateAuroraConfigFile(affiliation, "$environment/$filename.json", fileContents)
        return Response(items = listOf(auroraConfig).map(::fromAuroraConfig))
    }

    @PutMapping("/{affiliation}/auroraconfig/{filename:\\w+}.json")
    fun updateAuroraConfigFile(@PathVariable affiliation: String, @PathVariable filename: String,
                               @RequestBody fileContents: JsonNode): Response {

        val auroraConfig = auroraConfigService.updateAuroraConfigFile(affiliation, "$filename.json", fileContents)
        return Response(items = listOf(auroraConfig).map(::fromAuroraConfig))
    }

    @PatchMapping(value = "/{affiliation}/auroraconfig/**", consumes = arrayOf("application/json-patch+json"))
    fun patchAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest,
                              @RequestBody jsonPatchOp: String): Response {

        val path = "affiliation/$affiliation/auroraconfig/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)

        val auroraConfig = auroraConfigService.patchAuroraConfigFile(affiliation, fileName, jsonPatchOp)
        return Response(items = listOf(auroraConfig).map(::fromAuroraConfig))
    }

}


