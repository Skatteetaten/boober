package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.boober.controller.internal.ApplyPayload
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.DeployService
import no.skatteetaten.aurora.boober.service.TagResult
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.renderSpecAsJson
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/apply/{auroraConfigName}")
class DeployControllerV1(private val deployService: DeployService, private val responder: DeployResponder) {

    @PutMapping
    fun apply(@PathVariable auroraConfigName: String, @RequestBody payload: ApplyPayload): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())

        val auroraDeployResults: List<DeployResponse> = deployService.executeDeploy(
            ref = ref,
            applicationDeploymentRefs = payload.applicationDeploymentRefs,
            overrides = payload.overridesToAuroraConfigFiles(),
            deploy = payload.deploy
        ).map {
            DeployResponse(
                deployId = it.deployId,
                success = it.success,
                ignored = it.ignored,
                reason = it.reason,
                tagResponse = it.tagResponse,
                projectExist = it.projectExist,
                openShiftResponses = it.openShiftResponses,
                deploymentSpec = it.auroraDeploymentSpecInternal?.let { internalSpec ->
                    renderSpecAsJson(internalSpec, true)
                }
            )
        }

        return responder.create(auroraDeployResults)
    }

    data class DeployResponse(
        val deploymentSpec: Map<String, Any>?,
        val deployId: String,
        val openShiftResponses: List<OpenShiftResponse>,
        val success: Boolean = true,
        val ignored: Boolean = false,
        val reason: String? = null,
        val tagResponse: TagResult? = null,
        val projectExist: Boolean = false
    )

    data class Deploy(
        val version: String
    )

    data class Environment(
        val namespace: String
    )
}

@Component
class DeployResponder {
    fun create(deployResponses: List<DeployControllerV1.DeployResponse>) =
        deployResponses.find { !it.success }
            ?.let { Response(items = deployResponses, success = false, message = it.reason ?: "Deploy failed") }
            ?: Response(items = deployResponses)
}