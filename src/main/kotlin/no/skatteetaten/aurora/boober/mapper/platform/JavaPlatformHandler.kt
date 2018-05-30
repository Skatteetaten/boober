package no.skatteetaten.aurora.boober.mapper.platform

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.v1.PortNumbers
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.stereotype.Component

@Component
class JavaPlatformHandler : ApplicationPlatformHandler("java") {
    override fun handleAuroraDeployment(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>, mounts: List<Mount>?, routeSuffix: String, sidecarContainers: List<AuroraContainer>?): AuroraDeployment {

        val tag = when (auroraDeploymentSpec.type) {
            development -> "latest"
            else -> "default"
        }
        val containers = listOf(AuroraContainer(
            name = "${auroraDeploymentSpec.name}-java",
            tcpPorts = mapOf("http" to PortNumbers.INTERNAL_HTTP_PORT, "management" to PortNumbers.INTERNAL_ADMIN_PORT, "jolokia" to PortNumbers.JOLOKIA_HTTP_PORT),
            readiness = auroraDeploymentSpec.deploy!!.readiness,
            liveness = auroraDeploymentSpec.deploy.liveness,
            limit = auroraDeploymentSpec.deploy.resources.limit,
            request = auroraDeploymentSpec.deploy.resources.request,
            env = createEnvVars(mounts, auroraDeploymentSpec, routeSuffix),
            mounts = mounts?.filter { it.targetContainer == null }
        ))
            .addIfNotNull(sidecarContainers)

        return AuroraDeployment(
            name = auroraDeploymentSpec.name,
            tag = tag,
            containers = containers,
            labels = createLabels(auroraDeploymentSpec.name, auroraDeploymentSpec.deploy, labels),
            mounts = mounts,
            annotations = createAnnotations(auroraDeploymentSpec),
            deployStrategy = auroraDeploymentSpec.deploy.deployStrategy,
            replicas = auroraDeploymentSpec.deploy.replicas,
            serviceAccount = auroraDeploymentSpec.deploy.serviceAccount,
            ttl = auroraDeploymentSpec.deploy.ttl)
    }

    override fun handlers(handlers: Set<AuroraConfigFieldHandler>): Set<AuroraConfigFieldHandler> {

        val buildHandlers = handlers.find { it.name.startsWith("baseImage") }
            ?.let {
                setOf(
                    AuroraConfigFieldHandler("baseImage/name", defaultValue = "flange"),
                    AuroraConfigFieldHandler("baseImage/version", defaultValue = "8")
                )
            }

        return handlers.addIfNotNull(buildHandlers)
    }
}
