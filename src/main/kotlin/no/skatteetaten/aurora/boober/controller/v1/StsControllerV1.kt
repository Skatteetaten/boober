package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.RenewRequest
import no.skatteetaten.aurora.boober.service.StsRenewService
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
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
            message = "Renewed cert for affiliaion=${payload.affiliation} namespace=${payload.namespace} name=${payload.name} with commonName=${payload.commonName}"
        )
    }
}