package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auroraconfig")
class AuroraConfigController(val auroraConfigService: AuroraConfigService) {

    @PutMapping("/{affiliation}")
    fun save(@PathVariable affiliation: String, @RequestBody auroraConfig: AuroraConfig) {

        auroraConfigService.withAuroraConfigForAffiliation(affiliation, true, {
            auroraConfig
        })
    }

    @GetMapping("/{affiliation}")
    fun get(@PathVariable affiliation: String): Response {

        return Response(items = listOf(auroraConfigService.findAuroraConfigForAffiliation(affiliation)))
    }

    @PutMapping("/{affiliation}/{fileName}")
    fun updateAuroraConfigFile(@PathVariable affiliation: String, @PathVariable fileName: String,
                               @RequestBody fileContents: Map<String, Any?>) {

        auroraConfigService.withAuroraConfigForAffiliation(affiliation, true, { auroraConfig: AuroraConfig ->
            auroraConfig.updateFile(fileName, fileContents)
        })
    }
}


