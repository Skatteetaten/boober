package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.*
import io.fabric8.kubernetes.api.model.*
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentCommand
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.mapper.PortNumbers
import no.skatteetaten.aurora.boober.service.AuroraResource
import no.skatteetaten.aurora.boober.service.Feature
import no.skatteetaten.aurora.boober.utils.addIfNotNull

val AuroraDeploymentSpec.toxiProxy: String?
    get() =
        this.featureEnabled("toxiproxy") {
            this["toxiproxy/version"]
        }

@org.springframework.stereotype.Service
class ToxiproxySidecarFeature() : Feature {

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
                AuroraConfigFieldHandler("toxiproxy", defaultValue = false, canBeSimplifiedConfig = true),
                AuroraConfigFieldHandler("toxiproxy/version", defaultValue = "2.1.3")
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, cmd: AuroraDeploymentCommand): Set<AuroraResource> {

        return adc.toxiProxy?.let {
            setOf(AuroraResource("${adc.name}-toxiproxy-config-cm",
                    newConfigMap {
                        metadata {
                            name = "${adc.name}-toxiproxy-config"
                            namespace = adc.namespace
                        }
                        data = mapOf("config.json" to getToxiProxyConfig())
                    }
            ))
        } ?: emptySet()

    }

    override fun modify(adc: AuroraDeploymentSpec, resources: Set<AuroraResource>, cmd: AuroraDeploymentCommand) {

        val toxiProxy = adc.toxiProxy ?: return

        val volume = newVolume {
            name = "${adc.name}-toxiproxy-config"
            configMap {
                name = "${adc.name}-toxiproxy-config"
            }
        }

        val container = createToxiProxyContainer(adc, toxiProxy)
        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it.resource)
                val podSpec = dc.spec.template.spec
                podSpec.volumes = podSpec.volumes.addIfNotNull(volume)
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            } else if (it.resource.kind == "Service") {
                val service: Service = jacksonObjectMapper().convertValue(it.resource)
                service.spec.ports.filter { p -> p.name == "http" }.forEach { port ->
                    port.targetPort = IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT)
                }
            }
        }
    }

    private fun createToxiProxyContainer(adc: AuroraDeploymentSpec, toxiproxyVersion: String): Container {
        val containerPorts = mapOf(
                "http" to PortNumbers.TOXIPROXY_HTTP_PORT,
                "management" to PortNumbers.TOXIPROXY_ADMIN_PORT
        )
        return newContainer {
            name = "${adc.name}-toxiproxy-sidecar"
            ports = containerPorts.map {
                newContainerPort {
                    name = it.key
                    containerPort = it.value
                    protocol = "TCP"
                }
            }
            env = containerPorts.map {
                val portName = if (it.key == "http") "HTTP_PORT" else "${it.key}_HTTP_PORT".toUpperCase()
                EnvVarBuilder().withName(portName).withValue(it.value.toString()).build()
            }
            volumeMounts = listOf(
                    newVolumeMount {
                        name = "${adc.name}-toxiproxy-config"
                        mountPath = "/u01/config/configmap"
                    }
            )
            resources {
                limits = mapOf(
                        "memory" to Quantity("256Mi"),
                        "cpu" to Quantity("1")
                )
                requests = mapOf(
                        "memory" to Quantity("128Mi"),
                        "cpu" to Quantity("10m")
                )
            }
            image = "shopify/toxiproxy:$toxiproxyVersion"
            readinessProbe = newProbe {
                tcpSocket {
                    port = IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT)
                }
                initialDelaySeconds = 10
                timeoutSeconds = 1
            }
            args = listOf("-config", "/u01/config/config.json")
        }
    }
}

data class ToxiProxyConfig(val name: String, val listen: String, val upstream: String)

fun getToxiProxyConfig(): String {
    val config = ToxiProxyConfig(
            name = "app",
            listen = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
            upstream = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT)

    return jacksonObjectMapper().writeValueAsString(listOf(config))
}