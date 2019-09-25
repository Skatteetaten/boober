package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.kubernetes.newOwnerReference
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.durationString
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant


val AuroraDeploymentSpec.ttl: Duration? get() = this.getOrNull("ttl")

@Service
class ApplicationDeploymentFeature() : Feature {

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
                AuroraConfigFieldHandler("message"),
                AuroraConfigFieldHandler("ttl", validator = { it.durationString() })
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraResource> {

        val applicationDeploymentId = DigestUtils.sha1Hex("${adc.namespace}/${adc.name}")
        val ttl = adc.ttl?.let {
            val removeInstant = Instants.now + it
            "removeAfter" to removeInstant.epochSecond.toString()
        }

        return setOf(AuroraResource("${adc.name}-ad", ApplicationDeployment(
                spec = ApplicationDeploymentSpec(
                        selector = mapOf("name" to adc.name),
                        message = adc.getOrNull("message"),
                        applicationDeploymentName = adc.name,
                        applicationDeploymentId = applicationDeploymentId,
                        command = ApplicationDeploymentCommand(
                                cmd.overrideFiles,
                                cmd.adr,
                                cmd.auroraConfigRef)
                ),
                _metadata = newObjectMeta {
                    name = adc.name
                    namespace = adc.namespace
                    labels = mapOf("id" to applicationDeploymentId).addIfNotNull(ttl)
                }
        )))
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraDeploymentCommand) {

        resources.forEach {
            if (it.resource.metadata.namespace != null && it.resource.kind != "ApplicationDeployment") {
                it.resource.metadata.ownerReferences = listOf(
                        newOwnerReference {
                            apiVersion = "skatteetaten.no/v1"
                            kind = "ApplicationDeployment"
                            name = adc.name
                            uid = "123-123"
                        }
                )
            }
        }
    }
}
