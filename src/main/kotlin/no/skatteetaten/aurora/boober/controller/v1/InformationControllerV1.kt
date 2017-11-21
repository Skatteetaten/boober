package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.BitbucketProjectService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class InformationControllerV1(val bitbucketProjectService: BitbucketProjectService) {

    @GetMapping("/v1/auroraconfignames")
    fun auroraConfigs(): Response = Response(items = bitbucketProjectService.getAllSlugs())
}