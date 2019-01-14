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

    @PutMapping()
    fun apply(@PathVariable auroraConfigName: String, @RequestBody payload: ApplyPayload): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())
        val auroraDeployResults: List<DeployResponse> = deployService.executeDeploy(
            ref,
            payload.allRefs,
            payload.overridesToAuroraConfigFiles(),
            payload.deploy
        ).map {
            val spec = it.auroraDeploymentSpecInternal?.let {
                Spec(
                    name = it.name,
                    cluster = it.cluster,
                    deploy = it.deploy?.let {
                        Deploy(it.version)
                    },
                    environment = Environment(it.environment.namespace)
                )
            }
            DeployResponse(
                deployId = it.deployId,
                success = it.success,
                ignored = it.ignored,
                reason = it.reason,
                tagResponse = it.tagResponse,
                projectExist = it.projectExist,
                openShiftResponses = it.openShiftResponses,
                auroraDeploymentSpec = spec,
                deploymentSpec = it.auroraDeploymentSpecInternal?.let {
                    renderSpecAsJson(it.spec, true)
                }
            )
        }

        return responder.create(auroraDeployResults)
    }

    data class DeployResponse(
        val auroraDeploymentSpec: Spec? = null, // TODO: remove this when AO is updated
        val deploymentSpec: Map<String, Any>?,
        val deployId: String,
        val openShiftResponses: List<OpenShiftResponse>,
        val success: Boolean = true,
        val ignored: Boolean = false,
        val reason: String? = null,
        val tagResponse: TagResult? = null,
        val projectExist: Boolean = false
    )

    data class Spec(
        val name: String,
        val cluster: String,
        val deploy: Deploy?,
        val environment: Environment
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