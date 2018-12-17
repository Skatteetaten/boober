package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.Responder
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class AuroraConfigNamesControllerV1(
    private val auroraConfigService: AuroraConfigService,
    private val responder: Responder
) {

    @GetMapping("/v1/auroraconfignames")
    fun auroraConfigs() = responder.create(auroraConfigService.findAllAuroraConfigNames())
}
