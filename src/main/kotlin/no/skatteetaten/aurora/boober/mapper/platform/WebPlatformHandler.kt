package no.skatteetaten.aurora.boober.mapper.platform

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.TemplateType.development
import no.skatteetaten.aurora.boober.platform.ApplicationPlatformHandler
import no.skatteetaten.aurora.boober.platform.AuroraContainer
import no.skatteetaten.aurora.boober.platform.AuroraDeployment
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.stereotype.Component

@Component
class WebPlatformHandler : ApplicationPlatformHandler("web") {
    override fun handleAuroraDeployment(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>, mounts: List<Mount>?): AuroraDeployment {

        val tag = when (auroraDeploymentSpec.type) {
            development -> "latest"
            else -> "default"
        }

        //TODO: What contains should have mounts and env and what should we do with limit/request/liveness?readiness?
        val container = listOf(
          AuroraContainer(
            name = "${auroraDeploymentSpec.name}-node",
            args = listOf("/u01/bin/run_node"),
            tcpPorts = mapOf("http" to 9090, "management" to 8081),
            readiness = auroraDeploymentSpec.deploy!!.readiness,
            liveness = auroraDeploymentSpec.deploy.liveness,
            limit = auroraDeploymentSpec.deploy.resources.limit,
            request = auroraDeploymentSpec.deploy.resources.request,
            env = createEnvVars(mounts, auroraDeploymentSpec),
            mounts = mounts
          ),
          AuroraContainer(
            name = "${auroraDeploymentSpec.name}-nginx",
            args = listOf("/u01/bin/run_nginx"),
            tcpPorts = mapOf("http" to 8080),
            readiness = auroraDeploymentSpec.deploy.readiness,
            liveness = auroraDeploymentSpec.deploy.liveness,
            limit = auroraDeploymentSpec.deploy.resources.limit,
            request = auroraDeploymentSpec.deploy.resources.request,
            env = createEnvVars(mounts, auroraDeploymentSpec),
            mounts = mounts
          )
        )


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
            setOf(AuroraConfigFieldHandler("baseImage/name", defaultValue = "wrench"),
              AuroraConfigFieldHandler("baseImage/version", defaultValue = "0"))
        }
        return handlers.addIfNotNull(buildHandlers)
    }
}
