package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.*
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.filterNullValues
import no.skatteetaten.aurora.boober.utils.withNonBlank
import org.springframework.stereotype.Service

@Service
class CommonLabelFeature(val userDetailsProvider: UserDetailsProvider) : Feature {

    //all handlers are in  header
    override fun handlers(header: AuroraDeploymentContext): Set<AuroraConfigFieldHandler> {

        //TODO: Do not like this
        val version = when (header.type) {
            in listOf(TemplateType.deploy, TemplateType.development) -> deployVersion
            in listOf(TemplateType.localTemplate, TemplateType.template) -> templateVersion
            else -> null
        }
        return setOf(
                AuroraConfigFieldHandler("management", defaultValue = true, canBeSimplifiedConfig = true),
                AuroraConfigFieldHandler("management/path", defaultValue = "actuator"),
                AuroraConfigFieldHandler("management/port", defaultValue = "8081"),
                AuroraConfigFieldHandler("releaseTo"),
                AuroraConfigFieldHandler("alarm", defaultValue = true),
                AuroraConfigFieldHandler("pause", defaultValue = false)

        ).addIfNotNull(version)
    }

    fun createCommonLabels(adc: AuroraDeploymentContext): Map<String, String> {
        val labels = mapOf(
                "app" to adc.name,
                "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
                "affiliation" to adc.affiliation,
                "updateInBoober" to "true",
                "booberDeployId" to adc.deployId,
                "name" to adc.name
        )

        return OpenShiftObjectLabelService.toOpenShiftLabelNameSafeMap(labels)
    }

    override fun modify(adc: AuroraDeploymentContext, resources: Set<AuroraResource>) {
        val labels = createCommonLabels(adc)
        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                val dcLabels = labels + createDcLabels(adc)
                val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
                if (dc.spec.template.metadata == null) {
                    dc.spec.template.metadata = ObjectMeta()
                }

                dc.spec.template.metadata.labels = dc.spec.template.metadata.labels.createOrAdd(dcLabels)

                it.resource.metadata.labels = it.resource.metadata.labels.createOrAdd(dcLabels)
                it.resource.metadata.annotations = it.resource.metadata.annotations.createOrAdd(createDcAnnotations(adc))

            } else {
                it.resource.metadata.labels = it.resource.metadata.labels.createOrAdd(labels)
            }
        }
    }

    fun Map<String, String>?.createOrAdd(values: Map<String, String>): Map<String, String> =
            this?.addIfNotNull(values) ?: values

    fun createDcLabels(adc: AuroraDeploymentContext): Map<String, String> {

        val pauseLabel = if (adc["pause"]) {
            "paused" to "true"
        } else null

        val allLabels = mapOf("deployTag" to adc.dockerTag).addIfNotNull(pauseLabel)
        return OpenShiftObjectLabelService.toOpenShiftLabelNameSafeMap(allLabels)
    }

    fun createDcAnnotations(adc: AuroraDeploymentContext): Map<String, String> {

        fun escapeOverrides(): String? {
            val files =
                    adc.overrideFiles.mapValues { jacksonObjectMapper().readValue(it.value, JsonNode::class.java) }
            val content = jacksonObjectMapper().writeValueAsString(files)
            return content.takeIf { it != "{}" }
        }

        return mapOf(
                "boober.skatteetaten.no/applicationFile" to adc.applicationFile.name,
                "console.skatteetaten.no/alarm" to adc["alarm"],
                "boober.skatteetaten.no/overrides" to escapeOverrides(),
                "console.skatteetaten.no/management-path" to adc.managementPath,
                "boober.skatteetaten.no/releaseTo" to adc.releaseTo
        ).filterNullValues().filterValues { !it.isBlank() }
    }

}
/*
"labels" : {
      "app" : "openshift-console-api",
      "updatedBy" : "hero",
      "affiliation" : "aos",
      "updateInBoober" : "true",
      "booberDeployId" : "123",
      "name" : "openshift-console-api",
      "deployTag" : "3"
    },
 */