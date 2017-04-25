package no.skatteetaten.aurora.boober.controller

import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auroraconfig")
class AuroraConfigController(val auroraConfigService: AuroraConfigService) {

    @PutMapping("/{affiliation}")
    fun save(@PathVariable affiliation: String, @RequestBody auroraConfig: AuroraConfig) {

        auroraConfigService.save(affiliation, auroraConfig)
    }

    @GetMapping("/{affiliation}")
    fun get(@PathVariable affiliation: String): Response {

        return Response(items = listOf(auroraConfigService.findAuroraConfig(affiliation)))
    }

    @PutMapping("/{affiliation}/{fileName}")
    fun updateAuroraConfigFile(@PathVariable affiliation: String, @PathVariable fileName: String,
                               @RequestBody fileContents: Map<String, Any?>) {

        auroraConfigService.withAuroraConfig(affiliation, true, { auroraConfig: AuroraConfig ->
            auroraConfig.updateFile(fileName, fileContents)
        })
    }
}


