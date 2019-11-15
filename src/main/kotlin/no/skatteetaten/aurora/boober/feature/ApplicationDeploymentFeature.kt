package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.newObjectMeta
import com.fkorotkov.kubernetes.newOwnerReference
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentSpec
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.durationString
import no.skatteetaten.aurora.boober.utils.normalizeLabels
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.boot.convert.DurationStyle.SIMPLE
import org.springframework.stereotype.Service
import java.time.Duration

val AuroraDeploymentSpec.ttl: Duration? get() = this.getOrNull<String>("ttl")?.let { SIMPLE.parse(it) }

@Service
class ApplicationDeploymentFeature : Feature {

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler("message"),
            AuroraConfigFieldHandler("ttl", validator = { it.durationString() })
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {

        val applicationDeploymentId = DigestUtils.sha1Hex("${adc.namespace}/${adc.name}")
        val ttl = adc.ttl?.let {
            val removeInstant = Instants.now + it
            "removeAfter" to removeInstant.epochSecond.toString()
        }

        val resource = ApplicationDeployment(
            spec = ApplicationDeploymentSpec(
                selector = mapOf("name" to adc.name),
                updatedAt = Instants.now.toString(),
                message = adc.getOrNull("message"),
                applicationDeploymentName = adc.name,
                applicationDeploymentId = applicationDeploymentId,
                command = ApplicationDeploymentCommand(
                    cmd.overrideFiles,
                    cmd.applicationDeploymentRef,
                    cmd.auroraConfigRef
                )
            ),
            _metadata = newObjectMeta {
                name = adc.name
                namespace = adc.namespace
                labels = mapOf("id" to applicationDeploymentId).addIfNotNull(ttl).normalizeLabels()
            }
        )
        return setOf(generateResource(resource))
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {

        resources.forEach {
            if (it.resource.metadata.namespace != null && it.resource.kind !in listOf(
                    "ApplicationDeployment",
                    "RoleBinding"
                )
            ) {
                modifyResource(it, "Set owner reference to ApplicationDeployment")
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
