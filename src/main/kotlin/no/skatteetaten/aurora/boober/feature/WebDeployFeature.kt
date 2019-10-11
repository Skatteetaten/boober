package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.openshift.customStrategy
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.openshift.api.model.BuildConfig
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.AuroraResourceSource
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class WebDeployFeature(@Value("\${integrations.docker.registry}") val registry: String) :
    AbstractDeployFeature(registry) {

    override fun createContainers(adc: AuroraDeploymentSpec): List<Container> {
        return listOf(
            createContainer(
                adc = adc,
                containerName = "${adc.name}-node",
                containerArgs = listOf("/u01/bin/run_node"),
                containerPorts = mapOf(
                    "http" to PortNumbers.NODE_PORT,
                    "management" to PortNumbers.INTERNAL_ADMIN_PORT
                )
            ),
            createContainer(
                adc = adc,
                containerName = "${adc.name}-nginx",
                containerArgs = listOf("/u01/bin/run_nginx"),
                containerPorts = mapOf("http" to PortNumbers.INTERNAL_HTTP_PORT)
            )

        )
    }

    override fun enable(platform: ApplicationPlatform) = platform == ApplicationPlatform.web

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        resources.forEach {
            if (it.resource.kind == "BuildConfig") {
                it.sources.addIfNotNull(
                    AuroraResourceSource(
                        feature = this::class.java,
                        comment = "Set applicationType in build"
                    )
                )
                val bc: BuildConfig = jacksonObjectMapper().convertValue(it.resource)
                bc.spec.strategy.customStrategy {
                    env.add(
                        newEnvVar {
                            name = "APPLICATION_TYPE"
                            value = "nodejs"
                        }
                    )
                }
            }
        }
        super.modify(adc, resources, cmd)
    }
}