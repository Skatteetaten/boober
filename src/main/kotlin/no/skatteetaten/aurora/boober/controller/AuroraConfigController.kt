package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.ApplicationId
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletRequest


@RestController
@RequestMapping("/affiliation")
class AuroraConfigController(val auroraConfigService: AuroraConfigService) {

    @PutMapping("/{affiliation}/auroraconfig/")
    fun save(@PathVariable affiliation: String, @RequestBody payload: AuroraConfigPayload) {

        auroraConfigService.save(affiliation, payload.toAuroraConfig())
    }

    @GetMapping("/{affiliation}/auroraconfig/")
    fun get(@PathVariable affiliation: String): Response {

        return Response(items = listOf(auroraConfigService.findAuroraConfig(affiliation)).map(::fromAuroraConfig))
    }

    @PutMapping("/{affiliation}/auroraconfig/**")
    fun updateAuroraConfigFile(@PathVariable affiliation: String, request: HttpServletRequest,
                               @RequestBody fileContents: Map<String, Any?>) {

        val path = "affiliation/$affiliation/auroraconfig/**"
        val fileName = AntPathMatcher().extractPathWithinPattern(path, request.requestURI)
        auroraConfigService.withAuroraConfig(affiliation, true, { auroraConfig: AuroraConfig ->
            auroraConfig.updateFile(fileName, fileContents)
        })
    }
}


