package no.skatteetaten.aurora.boober.controller.v1

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.facade.DeployFacade
import no.skatteetaten.aurora.boober.feature.applicationDeploymentId
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.TagResult
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.renderSpecAsJson

@RestController
@RequestMapping("/v1/apply/{auroraConfigName}")
class DeployControllerV1(private val deployFacade: DeployFacade) {

    @PutMapping
    fun apply(
        @PathVariable auroraConfigName: String,
        @RequestBody payload: ApplyPayload
    ): Response {

        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())

        val auroraDeployResults: List<DeployResponse> = deployFacade.executeDeploy(
            ref = ref,
            applicationDeploymentRefs = payload.applicationDeploymentRefs,
            overrides = payload.overridesToAuroraConfigFiles(),
            deploy = payload.deploy
        ).map {
            DeployResponse(
                auroraConfigRef = it.command.auroraConfig,
                deployId = it.deployId,
                success = it.success,
                reason = it.reason,
                tagResponse = it.tagResponse,
                projectExist = it.projectExist,
                openShiftResponses = it.openShiftResponses,
                deploymentSpec = renderSpecAsJson(it.auroraDeploymentSpecInternal, true),
                warnings = it.warnings,
                applicationDeploymentId = it.auroraDeploymentSpecInternal.applicationDeploymentId
            )
        }

        return auroraDeployResults.find { !it.success }
            ?.let {
                Response(
                    items = auroraDeployResults, success = false,
                    message = when (auroraDeployResults.size) {
                        1 -> it.reason ?: "Unknown error"
                        else -> "Deploy failed"
                    }
                )
            } ?: Response(items = auroraDeployResults)
    }
}

data class DeployResponse(
    val auroraConfigRef: AuroraConfigRef,
    val applicationDeploymentId: String,
    val deploymentSpec: Map<String, Any>?,
    val deployId: String,
    val openShiftResponses: List<OpenShiftResponse>,
    val success: Boolean = true,
    val reason: String? = null,
    val tagResponse: TagResult? = null,
    val projectExist: Boolean = false,
    val warnings: List<String> = emptyList()
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
