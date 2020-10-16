package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.resources
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.springframework.beans.factory.annotation.Value

val AuroraDeploymentSpec.loggingIndex: String? get() = this.getOrNull<String>("logging/index")
val AuroraDeploymentSpec.fluentConfigName: String? get() = "${this.name}-fluent-config"

@org.springframework.stereotype.Service
class FluentbitSidecarFeature(
    @Value("\${splunk.hec.token}") val hecToken: String
) : Feature {

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        // TODO validate logic
        return header.type in listOf(
                TemplateType.deploy,
                TemplateType.development
        )
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler("logging/index")
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraResource> {
        val index = adc.loggingIndex ?: return emptySet()

        val resource = newConfigMap {
            metadata {
                name = adc.fluentConfigName
                namespace = adc.namespace
            }
            data = mapOf("fluent-bit.conf" to generateFluentBitConfig(index, adc.name, hecToken, adc.cluster))
        }
        return setOf(generateResource(resource))
    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraContextCommand) {
        if (adc.loggingIndex == null) return

        val volume = newVolume {
            name = adc.fluentConfigName
            configMap {
                name = adc.fluentConfigName
            }
        }

        val container = createFluentbitContainer(adc)
        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                modifyResource(it, "Added fluentbit volume and sidecar container")
                val dc: DeploymentConfig = it.resource as DeploymentConfig
                val podSpec = dc.spec.template.spec
                podSpec.volumes = podSpec.volumes.addIfNotNull(volume)
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            } else if (it.resource.kind == "Deployment") {
                // TODO: refactor
                modifyResource(it, "Added fluentbit volume and sidecar container")
                val dc: Deployment = it.resource as Deployment
                val podSpec = dc.spec.template.spec
                podSpec.volumes = podSpec.volumes.addIfNotNull(volume)
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            }
        }
    }

    private fun createFluentbitContainer(adc: AuroraDeploymentSpec): Container {
        return newContainer {
            name = "${adc.name}-fluent-sidecar"
            env = podEnvVariables
            volumeMounts = listOf(
                newVolumeMount {
                    name = adc.fluentConfigName
                    mountPath = "/fluent-bit/etc"
                }, loggingMount
            )
            resources {
                limits = mapOf(
                    // TODO? Add as config parameter
                    "memory" to Quantity("64Mi"),
                    "cpu" to Quantity("100m")
                )
                requests = mapOf(
                    "memory" to Quantity("20Mi"),
                    "cpu" to Quantity("10m")
                )
            }
            image = "fluent/fluent-bit:latest"
        }
    }
}

fun generateFluentBitConfig(index: String, application: String, hecToken: String, cluster: String): String {
    return """
    [SERVICE]
    Flush        1
    Daemon       Off
    Log_Level    debug
    Parsers_File parsers.conf

    [INPUT]
    Name   tail
    Path   /u01/logs/*.log
    Tag    log4j
    Mem_Buf_Limit 10MB
    Key    event

    [FILTER]
    Name modify
    Match *
    Add index $index
    Add host \$\{POD_NAME\}
    Add environment \$\{POD_NAMESPACE\}
    Add nodetype openshift
    Add application $application
    Add cluster $cluster

    [FILTER]
    Name nest
    Match *
    Operation nest
    Wildcard environment
    Wildcard application
    Wildcard cluster
    Wildcard nodetype
    Nest_under fields

    [OUTPUT]
    Name splunk
    Match *
    Host splunk-hec.skead.no
    Port 8088
    Splunk_Token $hecToken
    Splunk_Send_Raw On
    TLS         On
    TLS.Verify  Off
    TLS.Debug 0

    # Debug
    [OUTPUT]
    Name file
    Match *
    Path /u01/logs
    """
}
