package no.skatteetaten.aurora.boober.controller.v1

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade

@RestController
class AuroraConfigNamesControllerV1(
    private val facade: AuroraConfigFacade
) {

    @GetMapping("/v1/auroraconfignames")
    fun auroraConfigs() = Response(items = facade.findAllAuroraConfigNames())
}
