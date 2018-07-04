package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.RenewRequest
import no.skatteetaten.aurora.boober.service.StsRenewService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/sts")
class StsControllerV1(val service: StsRenewService) {

    @PostMapping()
    fun apply(@RequestBody payload: RenewRequest): Response {

        val items = service.renew(payload)

        return Response(
            items = items
        )
    }
}