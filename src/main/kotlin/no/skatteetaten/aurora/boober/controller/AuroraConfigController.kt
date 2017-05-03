package no.skatteetaten.aurora.boober.controller

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest


@RestController
@RequestMapping("/auroraconfig")
class AuroraConfigController(val auroraConfigService: AuroraConfigService) {

    @PutMapping("/{affiliation}")
    fun save(@PathVariable affiliation: String, @RequestBody payload: AuroraConfigPayload) {

        auroraConfigService.save(affiliation, payload.toAuroraConfig())
    }

    @GetMapping("/{affiliation}")
    fun get(@PathVariable affiliation: String): Response {

        return Response(items = listOf(auroraConfigService.findAuroraConfig(affiliation)).map(::fromAuroraConfig))
    }

    @PutMapping("/{affiliation}/**")
    fun updateAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest,
                               @RequestBody fileContents: JsonNode) {

        val fileName = AntPathMatcher().extractPathWithinPattern("/auroraconfig/$affiliation/**", request.requestURI)
        auroraConfigService.withAuroraConfig(affiliation, true, { auroraConfig: AuroraConfig ->
            auroraConfig.updateFile(fileName, fileContents)
        })
    }
}


