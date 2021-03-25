package no.skatteetaten.aurora.boober.feature

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.openshift.customStrategy
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.openshift.api.model.BuildConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.Paths.binPath
import no.skatteetaten.aurora.boober.model.PortNumbers

@Service
class WebDeployFeature(@Value("\${integrations.docker.registry}") val registry: String) :
    AbstractDeployFeature(registry) {

    override fun createContainers(adc: AuroraDeploymentSpec): List<Container> {
        return listOf(
            createContainer(
                adc = adc,
                containerName = "${adc.name}-node",
                containerArgs = listOf("$binPath/run_node"),
                containerPorts = mapOf(
                    "http" to PortNumbers.NODE_PORT,
                    "management" to PortNumbers.INTERNAL_ADMIN_PORT,
                    "extra" to PortNumbers.EXTRA_APPLICATION_PORT
                )
            ),
            createContainer(
                adc = adc,
                containerName = "${adc.name}-nginx",
                containerArgs = listOf("$binPath/run_nginx"),
                containerPorts = mapOf("http" to PortNumbers.INTERNAL_HTTP_PORT)
            )

        )
    }

    override fun enable(platform: ApplicationPlatform) = platform == ApplicationPlatform.web

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {
        resources.forEach {
            if (it.resource.kind == "BuildConfig") {

                modifyResource(it, "Set applicationType in build")
                val bc: BuildConfig = it.resource as BuildConfig
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
        super.modify(adc, resources, emptyMap())
    }
}
