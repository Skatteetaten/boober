package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.service.ApplicationDeploymentService
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ApplicationDeploymentRefPayload(val adr: List<ApplicationDeploymentRef>)
data class ApplicationDeploymentPayload(val applicationRefs: List<ApplicationRef>)
data class ApplicationRef(val namespace: String, val name: String)

data class DeleteResponse(
    val applicationRef: ApplicationRef,
    val success: Boolean,
    val reason: String
)

data class ExistsResponse(
    val applicationRef: ApplicationRef,
    val exists: Boolean,
    val success: Boolean,
    val message: String
)

@RestController
@RequestMapping("/v1/applicationdeployment")
class ApplicationDeploymentController(
    val applicationDeploymentService: ApplicationDeploymentService,
    val auroraConfigService: AuroraConfigService
) {

    @PostMapping("/delete")
    fun delete(@RequestBody applicationDeploymentPayload: ApplicationDeploymentPayload): Response {

        val applicationDeploymentDeleteResponse =
            applicationDeploymentService.executeDelete(applicationDeploymentPayload.applicationRefs)

        val deleteResponses = applicationDeploymentDeleteResponse.map {
            DeleteResponse(applicationRef = it.applicationRef, success = it.success, reason = it.message)
        }

        return Response(
            items = deleteResponses,
            success = deleteResponses.any { !it.success },
            message = deleteResponses.find { it.reason.toUpperCase() != "OK" }?.reason ?: "OK"
        )
    }

    @PostMapping("/{auroraConfigName}")
    fun applicationDeploymentExists(
        @PathVariable auroraConfigName: String,
        @RequestBody adrPayload: ApplicationDeploymentRefPayload
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val applicationRefs = auroraConfigService.createValidatedAuroraDeploymentSpecs(ref, adrPayload.adr).map {
            ApplicationRef(it.environment.namespace, it.name)
        }

        val applicationDeploymentResponse =
            applicationDeploymentService.checkApplicationDeploymentsExists(applicationRefs)

        val existsResponse = applicationDeploymentResponse.map {
            ExistsResponse(
                applicationRef = it.applicationRef,
                exists = it.exists,
                success = it.success,
                message = it.message
            )
        }

        return Response(
            items = existsResponse,
            success = existsResponse.any { !it.success },
            message = existsResponse.find { it.message.toUpperCase() != "OK" }?.message ?: "OK"
        )
    }
}

