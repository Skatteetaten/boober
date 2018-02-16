package no.skatteetaten.aurora.boober.mapper.platform

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.stereotype.Component

@Component
class JavaPlatformHandler : ApplicationPlatformHandler("java") {
    override fun handleAuroraDeployment(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>, mounts: List<Mount>?): AuroraDeployment {


        val tag = when (auroraDeploymentSpec.type) {
            development -> "latest"
            else -> "default"
        }
        val container = listOf(AuroraContainer(
                name = "${auroraDeploymentSpec.name}-java",
                tcpPorts = mapOf("http" to 8080, "management" to 8081, "jolokia" to 8778),
                readiness = auroraDeploymentSpec.deploy!!.readiness,
                liveness = auroraDeploymentSpec.deploy.liveness,
                limit = auroraDeploymentSpec.deploy.resources.limit,
                request = auroraDeploymentSpec.deploy.resources.request,
                env = createEnvVars(mounts, auroraDeploymentSpec),
                mounts = mounts
        ))


        return AuroraDeployment(
                name = auroraDeploymentSpec.name,
                tag = tag,
                containers = container,
                labels = createLabels(auroraDeploymentSpec.name, auroraDeploymentSpec.deploy, labels),
                mounts = mounts,
                annotations = createAnnotations(auroraDeploymentSpec.deploy),
                deployStrategy = auroraDeploymentSpec.deploy.deployStrategy,
                replicas = auroraDeploymentSpec.deploy.replicas,
                serviceAccount = auroraDeploymentSpec.deploy.serviceAccount)

    }


    override fun handlers(handlers: Set<AuroraConfigFieldHandler>): Set<AuroraConfigFieldHandler> {

        val buildHandlers = handlers.find { it.name.startsWith("baseImage") }?.let {
            setOf(
                    AuroraConfigFieldHandler("baseImage/name", defaultValue = "flange"),
                    AuroraConfigFieldHandler("baseImage/version", defaultValue = "8")
            )
        }

        return handlers.addIfNotNull(buildHandlers)

    }
}

