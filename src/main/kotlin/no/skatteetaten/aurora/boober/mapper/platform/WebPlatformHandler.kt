package no.skatteetaten.aurora.boober.mapper.platform

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.v1.PortNumbers
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.stereotype.Component

@Component
class WebPlatformHandler : ApplicationPlatformHandler("web") {
    override fun handleAuroraDeployment(
        auroraDeploymentSpecInternal: AuroraDeploymentSpecInternal,
        labels: Map<String, String>,
        mounts: List<Mount>?,
        routeSuffix: String,
        sidecarContainers: List<AuroraContainer>?
    ): AuroraDeployment {
        val tag = when (auroraDeploymentSpecInternal.type) {
            development -> "latest"
            else -> "default"
        }

        // TODO: What contains should have mounts and env and what should we do with limit/request/liveness?readiness?
        val container = listOf(
            AuroraContainer(
                name = "${auroraDeploymentSpecInternal.name}-node",
                args = listOf("/u01/bin/run_node"),
                tcpPorts = mapOf("http" to PortNumbers.NODE_PORT, "management" to PortNumbers.INTERNAL_ADMIN_PORT),
                readiness = auroraDeploymentSpecInternal.deploy!!.readiness,
                liveness = auroraDeploymentSpecInternal.deploy.liveness,
                limit = auroraDeploymentSpecInternal.deploy.resources.limit,
                request = auroraDeploymentSpecInternal.deploy.resources.request,
                env = createEnvVars(mounts, auroraDeploymentSpecInternal, routeSuffix),
                mounts = mounts?.filter { it.targetContainer == null }
            ),
            AuroraContainer(
                name = "${auroraDeploymentSpecInternal.name}-nginx",
                args = listOf("/u01/bin/run_nginx"),
                tcpPorts = mapOf("http" to PortNumbers.INTERNAL_HTTP_PORT),
                readiness = auroraDeploymentSpecInternal.deploy.readiness,
                liveness = auroraDeploymentSpecInternal.deploy.liveness,
                limit = auroraDeploymentSpecInternal.deploy.resources.limit,
                request = auroraDeploymentSpecInternal.deploy.resources.request,
                env = createEnvVars(mounts, auroraDeploymentSpecInternal, routeSuffix),
                mounts = mounts?.filter { it.targetContainer == null }
            )
        ).addIfNotNull(sidecarContainers)

        return AuroraDeployment(
            name = auroraDeploymentSpecInternal.name,
            tag = tag,
            containers = container,
            labels = createLabels(auroraDeploymentSpecInternal.name, auroraDeploymentSpecInternal.deploy, labels),
            mounts = mounts,
            annotations = createAnnotations(auroraDeploymentSpecInternal),
            deployStrategy = auroraDeploymentSpecInternal.deploy.deployStrategy,
            replicas = auroraDeploymentSpecInternal.deploy.replicas,
            serviceAccount = auroraDeploymentSpecInternal.deploy.serviceAccount,
            ttl = auroraDeploymentSpecInternal.deploy.ttl,
            pause = auroraDeploymentSpecInternal.deploy.pause,
            namespace = auroraDeploymentSpecInternal.environment.namespace
        )
    }

    override fun handlers(type: TemplateType): Set<AuroraConfigFieldHandler> = when(type) {
        development -> setOf(
                AuroraConfigFieldHandler("baseImage/name", defaultValue = "wrench"),
                AuroraConfigFieldHandler("baseImage/version", defaultValue = "0")
            )
        else -> emptySet()
    }
}
