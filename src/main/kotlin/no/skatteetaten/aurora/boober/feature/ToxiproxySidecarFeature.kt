package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newContainerPort
import com.fkorotkov.kubernetes.newProbe
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.resources
import com.fkorotkov.kubernetes.tcpSocket
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.Paths.configPath
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.findSubKeysExpanded
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.allNonSideCarContainers
import no.skatteetaten.aurora.boober.utils.boolean

val AuroraDeploymentSpec.toxiProxy: String?
    get() =
        this.featureEnabled("toxiproxy") {
            this["toxiproxy/version"]
        }

@org.springframework.stereotype.Service
class ToxiproxySidecarFeature(
    cantusService: CantusService
) : AbstractResolveTagFeature(cantusService) {

    private val startPort = 18000 // Porter f.o.m. denne tildeles til toxiproxyproxies
    private var newPort = startPort - 1

    val toxiproxyConfigs: MutableList<ToxiProxyConfig> = mutableListOf(getDefaultToxiProxyConfig())

    override fun isActive(spec: AuroraDeploymentSpec): Boolean {
        val toxiProxy = spec.toxiProxy

        return toxiProxy != null
    }

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {

        val toxiProxyTag = spec.toxiProxy

        if (validationContext || toxiProxyTag == null) {
            return emptyMap()
        }

        return createImageMetadataContext(
            repo = "shopify",
            name = "toxiproxy",
            tag = toxiProxyTag
        )
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {

        val endpointHandlers = findEndpointHandlers(cmd.applicationFiles)
        val envVariables = findEnvVariables(cmd.applicationFiles)

        return (endpointHandlers + envVariables + listOf(
            AuroraConfigFieldHandler(
                "toxiproxy",
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler("toxiproxy/version", defaultValue = "2.1.3"),
            AuroraConfigFieldHandler("toxiproxy/endpoints")
        )).toSet()
    }

    fun findEndpointHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> =
        applicationFiles.findSubKeysExpanded("toxiproxy/endpoints").flatMap { endpoint ->
            val expandedEndpointKeys = applicationFiles.findSubKeys(endpoint)
            listOf(
                if (expandedEndpointKeys.isEmpty()) {
                    AuroraConfigFieldHandler(endpoint, defaultValue = false, validator = { it.boolean() })
                } else {
                    AuroraConfigFieldHandler("$endpoint/proxyname", defaultValue = endpoint)
                }
            )
        }

    fun findEnvVariables(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> =
        applicationFiles.findSubKeysExpanded("config").flatMap { variable ->
            listOf(
                AuroraConfigFieldHandler(variable)
            )
        }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {

        adc.toxiProxy ?: return emptySet()

        adc.extractToxiproxyEndpoints().forEach { (proxyname, varname) ->
            val url = adc.fields["config/$varname"]?.value<String>()
            if (url != null) {
                newPort++
                toxiproxyConfigs.add(ToxiProxyConfig(
                    name = proxyname,
                    listen = "0.0.0.0:$newPort",
                    upstream = url
                ))
            }
        }

        val configMap = newConfigMap {
            metadata {
                name = "${adc.name}-toxiproxy-config"
                namespace = adc.namespace
            }
            data = mapOf("config.json" to jacksonObjectMapper().writeValueAsString(toxiproxyConfigs))
        }

        return setOf(generateResource(configMap))
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {

        adc.toxiProxy ?: return

        val volume = newVolume {
            name = "${adc.name}-toxiproxy-config"
            configMap {
                name = "${adc.name}-toxiproxy-config"
            }
        }

        val container = createToxiProxyContainer(adc, context)
        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                modifyResource(it, "Added toxiproxy volume and sidecar container")
                val dc: DeploymentConfig = it.resource as DeploymentConfig
                val podSpec = dc.spec.template.spec
                podSpec.volumes = podSpec.volumes.addIfNotNull(volume)
                dc.allNonSideCarContainers.forEach { container ->
                    adc.extractToxiproxyEndpoints().forEach { (proxyname, varname) ->
                        val proxyAddress = toxiproxyConfigs
                            .find { toxiProxyConfig -> toxiProxyConfig.name == proxyname }!!
                            .listen
                        container.env.find { v -> v.name == varname }?.value = proxyAddress
                    }
                }
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            } else if (it.resource.kind == "Deployment") {
                // TODO: refactor
                modifyResource(it, "Added toxiproxy volume and sidecar container")
                val dc: Deployment = it.resource as Deployment
                val podSpec = dc.spec.template.spec
                podSpec.volumes = podSpec.volumes.addIfNotNull(volume)
                dc.allNonSideCarContainers.forEach { container ->
                    adc.extractToxiproxyEndpoints().forEach { (proxyname, varname) ->
                        val proxyAddress = toxiproxyConfigs
                            .find { toxiProxyConfig -> toxiProxyConfig.name == proxyname }!!
                            .listen
                        container.env.find { v -> v.name == varname }?.value = proxyAddress
                    }
                }
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            } else if (it.resource.kind == "Service") {
                val service: Service = it.resource as Service
                service.spec.ports.filter { p -> p.name == "http" }.forEach { port ->
                    port.targetPort = IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT)
                }

                modifyResource(it, "Changed targetPort to point to toxiproxy")
            }
        }
    }

    private fun createToxiProxyContainer(adc: AuroraDeploymentSpec, context: FeatureContext): Container {
        val containerPorts = mapOf(
            "http" to PortNumbers.TOXIPROXY_HTTP_PORT,
            "management" to PortNumbers.TOXIPROXY_ADMIN_PORT
        )

        val imageMetadata = context.imageMetadata

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
                    mountPath = "$configPath/toxiproxy"
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
            image = imageMetadata.getFullImagePath()
            readinessProbe = newProbe {
                tcpSocket {
                    port = IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT)
                }
                initialDelaySeconds = 10
                timeoutSeconds = 1
            }
            args = listOf("-config", "$configPath/toxiproxy/config.json")
        }
    }
}

data class ToxiProxyConfig(val name: String, val listen: String, val upstream: String)

fun getDefaultToxiProxyConfig() = ToxiProxyConfig(
    name = "app",
    listen = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
    upstream = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT
)

// Return a list of proxynames and corresponding environment variable names
// If proxyname is not set, it defaults to "endpoint_<variable name>"
fun AuroraDeploymentSpec.extractToxiproxyEndpoints(): List<Pair<String, String>> = this
    .getSubKeys("toxiproxy/endpoints")
    .map { (fieldName, field) ->
        if (fieldName.endsWith("/proxyname")) {
            Pair(
                field.value() as String,
                Regex("(?<=^toxiproxy\\/endpoints\\/)(.*)(?=\\/proxyname\$)").find(fieldName)!!.value
            )
        } else {
            val varname = Regex("(?<=^toxiproxy\\/endpoints\\/).*\$").find(fieldName)!!.value
            Pair("endpoint_$varname", varname)
        }
    }
