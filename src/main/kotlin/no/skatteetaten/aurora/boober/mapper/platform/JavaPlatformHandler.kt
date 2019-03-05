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
class JavaPlatformHandler : ApplicationPlatformHandler("java") {
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
        val containers = listOf(AuroraContainer(
            name = "${auroraDeploymentSpecInternal.name}-java",
            tcpPorts = mapOf(
                "http" to PortNumbers.INTERNAL_HTTP_PORT,
                "management" to PortNumbers.INTERNAL_ADMIN_PORT,
                "jolokia" to PortNumbers.JOLOKIA_HTTP_PORT
            ),
            readiness = auroraDeploymentSpecInternal.deploy!!.readiness,
            liveness = auroraDeploymentSpecInternal.deploy.liveness,
            limit = auroraDeploymentSpecInternal.deploy.resources.limit,
            request = auroraDeploymentSpecInternal.deploy.resources.request,
            env = createEnvVars(mounts, auroraDeploymentSpecInternal, routeSuffix),
            mounts = mounts?.filter { it.targetContainer == null }
        ))
            .addIfNotNull(sidecarContainers)

        return AuroraDeployment(
            name = auroraDeploymentSpecInternal.name,
            tag = tag,
            containers = containers,
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

    override fun handlers(type: TemplateType): Set<AuroraConfigFieldHandler> = when (type) {
        development -> setOf(
            AuroraConfigFieldHandler("baseImage/name", defaultValue = "wingnut8"),
            AuroraConfigFieldHandler("baseImage/version", defaultValue = "1")
        )
        else -> emptySet()
    }
}
