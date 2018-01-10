package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class AuroraConfigNameslControllerV1(val auroraConfigService: AuroraConfigService) {

    @GetMapping("/v1/auroraconfignames")
    fun auroraConfigs(): Response = Response(items = auroraConfigService.findAllAuroraConfigNames())
}