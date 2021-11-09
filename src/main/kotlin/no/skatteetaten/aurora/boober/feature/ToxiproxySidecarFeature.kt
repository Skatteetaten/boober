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
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.Paths.configPath
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.model.findSubKeys
import no.skatteetaten.aurora.boober.model.findSubKeysExpanded
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.allNonSideCarContainers
import no.skatteetaten.aurora.boober.utils.boolean
import java.net.URI

val AuroraDeploymentSpec.toxiProxy: String?
    get() =
        this.featureEnabled("toxiproxy") {
            this["toxiproxy/version"]
        }

const val FIRST_PORT_NUMBER = 18000 // The first Toxiproxy port will be set to this number

@org.springframework.stereotype.Service
class ToxiproxySidecarFeature(
    cantusService: CantusService
) : AbstractResolveTagFeature(cantusService) {

    val toxiproxyConfigs = mutableListOf<ToxiProxyConfig>()

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
            if (expandedEndpointKeys.isEmpty()) {
                listOf(AuroraConfigFieldHandler(endpoint, defaultValue = false, validator = { it.boolean() }))
            } else {
                listOf(
                    AuroraConfigFieldHandler("$endpoint/proxyname", defaultValue = endpoint),
                    AuroraConfigFieldHandler("$endpoint/enabled", defaultValue = true)
                )
            }
        }

    fun findEnvVariables(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> =
        applicationFiles.findSubKeysExpanded("config").flatMap { variable ->
            listOf(
                AuroraConfigFieldHandler(variable)
            )
        }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> = if (fullValidation) {
        // For every endpoint in toxiproxy/endpoints, there should be a corresponding environment variable
        adc.groupToxiproxyEndpointFields().keys.filter {
            varName -> !adc.getSubKeys("config").keys.map { it.removePrefix("config/") }.contains(varName)
        }.map {
            AuroraDeploymentSpecValidationException(
                "Found Toxiproxy config for endpoint named $it, but there is no such environment variable."
            )
        }
    } else { emptyList() }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {

        adc.toxiProxy ?: return emptySet()

        // Variable for the port number that Toxiproxy will listen to
        // An addition of 1 to the value is made for each proxy
        var port = FIRST_PORT_NUMBER

        toxiproxyConfigs.clear()
        toxiproxyConfigs.add(getDefaultToxiProxyConfig())

        adc.extractToxiproxyEndpoints().forEach { (proxyname, varname) ->
            val url = adc.fields["config/$varname"]?.value<String>()
            if (url != null) {
                val uri = URI(url)
                val upstreamPort = if (uri.port == -1) {
                    if (uri.scheme == "https") { 443 } else { 80 }
                } else {
                    uri.port
                }
                toxiproxyConfigs.add(ToxiProxyConfig(
                    name = proxyname,
                    listen = "0.0.0.0:$port",
                    upstream = uri.host + ":" + upstreamPort
                ))
                port++
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
                dc.allNonSideCarContainers.overrideEnvVarsWithProxies(adc)
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            } else if (it.resource.kind == "Deployment") {
                // TODO: refactor
                modifyResource(it, "Added toxiproxy volume and sidecar container")
                val dc: Deployment = it.resource as Deployment
                val podSpec = dc.spec.template.spec
                podSpec.volumes = podSpec.volumes.addIfNotNull(volume)
                dc.allNonSideCarContainers.overrideEnvVarsWithProxies(adc)
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

    fun List<Container>.overrideEnvVarsWithProxies(adc: AuroraDeploymentSpec) = this.forEach {
        adc.extractToxiproxyEndpoints().forEach { (proxyName, varName) ->
            val proxyAddress = toxiproxyConfigs
                .find { toxiProxyConfig -> toxiProxyConfig.name == proxyName }!!
                .listen
                .replace(Regex("^0\\.0\\.0\\.0"), "http://localhost")
            it.env.find { v -> v.name == varName }?.value = proxyAddress
        }
    }
}

data class ToxiProxyConfig(val name: String, val listen: String, val upstream: String)

fun getDefaultToxiProxyConfig() = ToxiProxyConfig(
    name = "app",
    listen = "0.0.0.0:" + PortNumbers.TOXIPROXY_HTTP_PORT,
    upstream = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT
)

// Regex for matching a variable name in a field name
val varNameInFieldNameRegex = Regex("(?<=^toxiproxy\\/endpoints\\/)([^\\/]+(?=\\/enabled\$|\\/proxyname\$|\$))")

// Return lists of AuroraConfigFields grouped by environment variable name
fun AuroraDeploymentSpec.groupToxiproxyEndpointFields(): Map<String, List<Map.Entry<String, AuroraConfigField>>> = this
    .getSubKeys("toxiproxy/endpoints")
    .map { it }
    .groupBy { varNameInFieldNameRegex.find(it.key)!!.value }

// Return a list of proxynames and corresponding environment variable names
// If proxyname is not set, it defaults to "endpoint_<variable name>"
fun AuroraDeploymentSpec.extractToxiproxyEndpoints(): List<Pair<String, String>> = this
    .groupToxiproxyEndpointFields()
    .filter { (varName, fields) ->
        if (fields.size == 1 && fields[0].key.endsWith(varName)) {
            fields[0]
        } else {
            fields.find { it.key.endsWith("/enabled") }
        }!!.value.value()
    }
    .map { (varName, fields) ->
        val proxyName = fields
            .find { it.key.endsWith("/proxyname") }
            ?.value
            ?.value<String>()
            ?: "endpoint_$varName"
        Pair(proxyName, varName)
    }
