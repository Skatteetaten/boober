package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.annotation.JsonIgnore
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.ApplicationDeploymentService
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ApplicationDeploymentPayload(val applicationRefs: List<ApplicationRef>)
data class ApplicationRef(val namespace: String, val name: String)

@RestController
@RequestMapping("/v1/applicationdeployment")
class ApplicationDeploymentController(
    val applicationDeploymentService: ApplicationDeploymentService,
    val applicationDeploymentDeleteResponder: ApplicationDeploymentResponder,
    val auroraConfigService: AuroraConfigService
) {

    @PostMapping("/delete")
    fun delete(@RequestBody applicationDeploymentPayload: ApplicationDeploymentPayload): Response {

        val applicationDeploymentDeleteResponse =
            applicationDeploymentService.executeDelete(applicationDeploymentPayload.applicationRefs)

        val deleteResponses = applicationDeploymentDeleteResponse.map {
            DeleteResponse(applicationRef = it.applicationRef, success = it.success, reason = it.message)
        }

        return applicationDeploymentDeleteResponder.createDeleteResponse(deleteResponses)
    }

    @PostMapping
    fun applicationDeploymentExists(@RequestBody applicationDeploymentPayload: ApplicationDeploymentPayload): Response {

        val getApplicationDeploymentResponse =
            applicationDeploymentService.checkApplicationDeploymentsExists(applicationDeploymentPayload.applicationRefs)

        val existsResponse = getApplicationDeploymentResponse.map {
            ExistsResponse(
                applicationRef = it.applicationRef,
                exists = it.exists,
                success = it.success,
                message = it.message
            )
        }

        return applicationDeploymentDeleteResponder.createExistsResponse(existsResponse)
    }
}

data class DeleteResponse(
    val applicationRef: ApplicationRef,
    val success: Boolean,
    val reason: String
)

data class ExistsResponse(
    val applicationRef: ApplicationRef,
    val exists: Boolean,
    @JsonIgnore val success: Boolean,
    @JsonIgnore val message: String
)

@Component
class ApplicationDeploymentResponder {

    fun createDeleteResponse(deleteResponse: List<DeleteResponse>) =
        // TODO: Verify that top message is the same as an item erorr message
        Response(
            items = deleteResponse,
            success = deleteResponse.any { !it.success },
            message = deleteResponse.find { it.reason.toUpperCase() != "OK" }?.reason ?: "OK"
        )

    fun createExistsResponse(existsResponse: List<ExistsResponse>) =
        Response(
            items = existsResponse,
            success = existsResponse.any { !it.success },
            message = existsResponse.find { it.message.toUpperCase() != "OK" }?.message ?: "OK"
        )
}