package no.skatteetaten.aurora.boober.controller.v1

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.facade.DeployFacade
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.TagResult
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.renderSpecAsJson
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/apply/{auroraConfigName}")
class DeployControllerV1(private val deployFacade: DeployFacade) {

    @PutMapping
    fun apply(@PathVariable auroraConfigName: String, @RequestBody payload: ApplyPayload): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())

        val auroraDeployResults: List<DeployResponse> = deployFacade.executeDeploy(
            ref = ref,
            applicationDeploymentRefs = payload.applicationDeploymentRefs,
            overrides = payload.overridesToAuroraConfigFiles(),
            deploy = payload.deploy
        ).map {
            DeployResponse(
                deployId = it.deployId,
                success = it.success,
                reason = it.reason,
                tagResponse = it.tagResponse,
                projectExist = it.projectExist,
                openShiftResponses = it.openShiftResponses,
                deploymentSpec = it.auroraDeploymentSpecInternal.let { internalSpec ->
                    renderSpecAsJson(internalSpec, true)
                }
            )
        }

        return auroraDeployResults.find { !it.success }
            ?.let { Response(items = auroraDeployResults, success = false, message = it.reason ?: "Deploy failed") }
            ?: Response(items = auroraDeployResults)
    }
}

data class DeployResponse(
    val deploymentSpec: Map<String, Any>?,
    val deployId: String,
    val openShiftResponses: List<OpenShiftResponse>,
    val success: Boolean = true,
    val reason: String? = null,
    val tagResponse: TagResult? = null,
    val projectExist: Boolean = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApplyPayload(
    val applicationDeploymentRefs: List<ApplicationDeploymentRef> = emptyList(),
    val overrides: Map<String, String> = mapOf(),
    val deploy: Boolean = true
) {
    fun overridesToAuroraConfigFiles(): List<AuroraConfigFile> {
        return overrides.map { AuroraConfigFile(it.key, it.value, true) }
    }
}
