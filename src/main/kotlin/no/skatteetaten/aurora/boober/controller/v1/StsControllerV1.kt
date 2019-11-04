package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.facade.RenewRequest
import no.skatteetaten.aurora.boober.facade.StsRenewFacade
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// TODO: FEATURE test error if skap url is not set
@RestController
@RequestMapping("/v1/sts")
@ConditionalOnProperty("integrations.skap.url")
class StsControllerV1(
    private val facade: StsRenewFacade
) {

    @PostMapping
    fun apply(@RequestBody payload: RenewRequest): Response {
        val items = facade.renew(payload)
        val failed = items.firstOrNull { !it.success }
        failed?.let {
            return Response(
                success = false,
                message = failed.exception ?: "Failed renewing certificate"
            )
        }
        return Response(
            success = true,
            message = "Renewed cert for affiliation=${payload.affiliation} namespace=${payload.namespace} name=${payload.name} with commonName=${payload.commonName}"
        )
    }
}
