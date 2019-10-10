package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.RenewRequest
import no.skatteetaten.aurora.boober.service.StsRenewService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// TODO: Inline responderen
@RestController
@RequestMapping("/v1/sts")
class StsControllerV1(
    private val service: StsRenewService,
    private val stsResponder: StsResponder
) {

    @PostMapping
    fun apply(@RequestBody payload: RenewRequest): Response {
        val items = service.renew(payload)
        return stsResponder.create(payload, items)
    }
}

@Component
class StsResponder {

    fun create(payload: RenewRequest, items: List<OpenShiftResponse>): Response {
        val failed = items.firstOrNull { !it.success }
        failed?.let {
            val cmd = it.command.payload
            return Response(
                success = false,
                message = failed.exception
                    ?: "Failed running command kind=${cmd.openshiftKind} name=${cmd.openshiftName}"
            )
        }
        return Response(
            success = true,
            message = "Renewed cert for affiliation=${payload.affiliation} namespace=${payload.namespace} name=${payload.name} with commonName=${payload.commonName}"
        )
    }
}
