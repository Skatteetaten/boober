package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.openshift.customStrategy
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.openshift.api.model.BuildConfig
import no.skatteetaten.aurora.boober.mapper.ApplicationPlatform
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.PortNumbers
import no.skatteetaten.aurora.boober.service.AuroraResource
import org.springframework.stereotype.Service

@Service
class WebDeployFeature(dockerRegistry: String) : AbstractDeployFeature(dockerRegistry) {
    override fun createContainers(adc: AuroraDeploymentSpec): List<Container> {
        return listOf(
                createContainer(
                        adc = adc,
                        containerName = "${adc.name}-node",
                        containerArgs = listOf("/u01/bin/run_node"),
                        containerPorts = mapOf("http" to PortNumbers.NODE_PORT,
                                "management" to PortNumbers.INTERNAL_ADMIN_PORT)),
                createContainer(
                        adc = adc,
                        containerName = "${adc.name}-nginx",
                        containerArgs = listOf("/u01/bin/run_nginx"),
                        containerPorts = mapOf("http" to PortNumbers.INTERNAL_HTTP_PORT))

        )
    }

    override fun enable(platform: ApplicationPlatform) = platform == ApplicationPlatform.web

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraDeploymentCommand) {
        resources.forEach {
            if (it.resource.kind == "BuildConfig") {
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