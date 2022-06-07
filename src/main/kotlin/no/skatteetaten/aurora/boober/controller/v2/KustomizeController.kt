package no.skatteetaten.aurora.boober.controller.v2

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.controller.v1.getRefNameFromRequest
import no.skatteetaten.aurora.boober.facade.DeployFacade
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.AuroraConfigRef

@RestController
@RequestMapping("/v2/kustomize")
class KustomizeController(val deployFacade: DeployFacade) {

    @GetMapping("/{auroraConfigName}")
    fun getKustomizeForApplication(
        @PathVariable auroraConfigName: String,
        @RequestParam environment: String,
        @RequestParam application: String
    ): Map<String, HasMetadata> {
        val ref = AuroraConfigRef(auroraConfigName, getRefNameFromRequest())

        return deployFacade.generateResources(
            ref = ref,
            applicationDeploymentRefs = listOf(ApplicationDeploymentRef(environment, application))
        )
            .filter { it !is Secret && it !is ApplicationDeployment }
            .map(this::normalizeResource)
            .associateBy { "${it.kind}-${it.metadata.name}.json".lowercase() }
    }

    private fun normalizeResource(it: HasMetadata): HasMetadata {
        it.metadata.ownerReferences = emptyList()
        it.metadata.labels = null
        when (it) {
            is DeploymentConfig -> it.spec.template.metadata.labels = it.spec.selector
        }
        return it
    }
}
