package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newEnvVar
import com.fkorotkov.kubernetes.newSecret
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.valueFrom
import com.fkorotkov.kubernetes.resources
import com.fkorotkov.kubernetes.secretKeyRef
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value

val AuroraDeploymentSpec.loggingIndex: String? get() = this.getOrNull<String>("logging/index")
val AuroraDeploymentSpec.fluentConfigName: String? get() = "${this.name}-fluent-config"
val AuroraDeploymentSpec.hecSecretName: String? get() = "${this.name}-hec"
val hecTokenKey: String = "HEC_TOKEN"
val splunkHostKey: String = "SPLUNK_HOST"
val splunkPortKey: String = "SPLUNK_PORT"

/*
Fluentbit sidecar feature provisions fluentd as sidecar with fluent bit configuration based on aurora config.
 */
@org.springframework.stereotype.Service
class FluentbitSidecarFeature(
    @Value("\${splunk.hec.token}") val hecToken: String,
    @Value("\${splunk.hec.url}") val splunkUrl: String,
    @Value("\${splunk.hec.port}") val splunkPort: String
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
            data = mapOf("fluent-bit.conf" to generateFluentBitConfig(index, adc.name, adc.cluster))
        }

        val hecSecret = newSecret {
            metadata {
                name = adc.hecSecretName
                namespace = adc.namespace
            }
            data = mapOf(hecTokenKey to Base64.encodeBase64String(hecToken.toByteArray()),
                    splunkHostKey to Base64.encodeBase64String(splunkUrl.toByteArray()),
                    splunkPortKey to Base64.encodeBase64String(splunkPort.toByteArray()))
        }
        return setOf(generateResource(resource), generateResource(hecSecret))
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
        val hecEnvVariables = listOf(
            newEnvVar {
                name = hecTokenKey
                valueFrom {
                    secretKeyRef {
                        key = hecTokenKey
                        name = adc.hecSecretName
                        optional = false
                    }
                }
            },
            newEnvVar {
                name = splunkHostKey
                valueFrom {
                    secretKeyRef {
                        key = splunkHostKey
                        name = adc.hecSecretName
                        optional = false
                    }
                }
            },
            newEnvVar {
                name = splunkPortKey
                valueFrom {
                    secretKeyRef {
                        key = splunkPortKey
                        name = adc.hecSecretName
                        optional = false
                    }
                }
            }
        )

        return newContainer {
            name = "${adc.name}-fluent-sidecar"
            env = podEnvVariables.addIfNotNull(hecEnvVariables)
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

fun generateFluentBitConfig(index: String, application: String, cluster: String): String {
    return """[SERVICE]
    Flush        1
    Daemon       Off
    Log_Level    debug
    Parsers_File parsers.conf

[INPUT]
    Name   tail
    Path   /u01/logs/*.log
    Tag    log.application
    Mem_Buf_Limit 10MB
    Key    event
    
[INPUT]
    Name   tail
    Path   /u01/logs/*.access
    Tag    log.access
    Mem_Buf_Limit 10MB
    Key    event
    
[FILTER]
    Name modify
    Match *.access
    Set sourcetype access_combined
    
[FILTER]
    Name modify
    Match *.application
    Set sourcetype log4j

[FILTER]
    Name modify
    Match *
    Add index $index
    Add host $ {POD_NAME}
    Add environment $ {POD_NAMESPACE}
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
    Host $ {SPLUNK_HOST}
    Port $ {SPLUNK_PORT}
    Splunk_Token $ {HEC_TOKEN}
    Splunk_Send_Raw On
    TLS         On
    TLS.Verify  Off
    TLS.Debug 0

    """.replace("$ {", "\${")
}
