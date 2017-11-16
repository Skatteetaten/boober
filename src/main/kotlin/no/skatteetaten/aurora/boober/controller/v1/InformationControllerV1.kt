package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.BitbucketService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class InformationControllerV1(val bitbucketService: BitbucketService) {

    @GetMapping("/v1/auroraconfig")
    fun auroraConfigs(): Response = Response(items = bitbucketService.auroraConfigs())
}