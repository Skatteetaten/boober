// ktlint-disable indent

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
import no.skatteetaten.aurora.boober.model.findConfigFieldHandlers
import no.skatteetaten.aurora.boober.model.findSubKeysExpanded
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.utils.allNonSideCarContainers
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.prependIfNotNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI

const val FIRST_PORT_NUMBER = 18000 // The first Toxiproxy port will be set to this number

@org.springframework.stereotype.Service
class ToxiproxySidecarFeature(
    cantusService: CantusService,
    @Value("\${toxiproxy.sidecar.default.version:2.1.3}") val sidecarVersion: String
) : AbstractResolveTagFeature(cantusService) {

    val toxiproxyConfigs = mutableListOf<ToxiProxyConfig>()

    override fun isActive(spec: AuroraDeploymentSpec): Boolean {
        val toxiproxyVersion = spec.toxiproxyVersion

        return toxiproxyVersion != null
    }

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {

        val toxiProxyTag = spec.toxiproxyVersion

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
        val envVariables = cmd.applicationFiles.findConfigFieldHandlers()

        return (
            endpointHandlers + envVariables + listOf(
                AuroraConfigFieldHandler(
                    "toxiproxy",
                    defaultValue = false,
                    validator = { it.boolean() },
                    canBeSimplifiedConfig = true
                ),
                AuroraConfigFieldHandler("toxiproxy/version", defaultValue = sidecarVersion),
                AuroraConfigFieldHandler("toxiproxy/endpoints")
            )
        ).toSet()
    }

    fun findEndpointHandlers(applicationFiles: List<AuroraConfigFile>): List<AuroraConfigFieldHandler> =
        applicationFiles.findSubKeysExpanded("toxiproxy/endpoints").flatMap { endpoint ->
            listOf(
                AuroraConfigFieldHandler(
                    endpoint,
                    defaultValue = true,
                    validator = { it.boolean() },
                    canBeSimplifiedConfig = true
                ),
                AuroraConfigFieldHandler(
                    "$endpoint/proxyname",
                    defaultValue = generateProxyNameFromVarName(findVarNameInFieldName(endpoint))
                ),
                AuroraConfigFieldHandler(
                    "$endpoint/enabled",
                    defaultValue = true,
                    validator = { it.boolean() }
                )
            )
        }

    override fun validate(
        adc: AuroraDeploymentSpec,
        fullValidation: Boolean,
        context: FeatureContext
    ): List<Exception> = if (fullValidation) {
        val groupedFields = adc.groupToxiproxyEndpointFields()

        // For every endpoint in toxiproxy/endpoints, there should be a corresponding environment variable
        val missingVariableErrors = groupedFields
            .keys
            .mapNotNull {
                varName ->
                if (
                    !adc.getSubKeys("config")
                        .keys
                        .any { it.removePrefix("config/") == varName }
                ) {
                    AuroraDeploymentSpecValidationException(
                        "Found Toxiproxy config for endpoint named $varName, " +
                            "but there is no such environment variable."
                    )
                } else null
            }

        // There should be no proxyname duplicates
        val proxynameDuplicateErrors = groupedFields
            .map {
                (varName, fields) ->
                fields
                    .find { it.key == "toxiproxy/endpoints/$varName/proxyname" }
                    ?.value
                    ?.value<String>()
                    ?: generateProxyNameFromVarName(varName)
            }
            .groupingBy { it }
            .eachCount()
            .filter { it.value > 1 }
            .map {
                AuroraDeploymentSpecValidationException(
                    "Found ${it.value} Toxiproxy configs with the proxy name \"${it.key}\". " +
                        "Proxy names have to be unique."
                )
            }

        listOf(missingVariableErrors, proxynameDuplicateErrors).flatten()
    } else { emptyList() }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {

        adc.toxiproxyVersion ?: return emptySet()

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
                    if (uri.scheme == "https") { PortNumbers.HTTPS_PORT } else { PortNumbers.HTTP_PORT }
                } else {
                    uri.port
                }
                toxiproxyConfigs.add(
                    ToxiProxyConfig(
                        name = proxyname,
                        listen = "0.0.0.0:$port",
                        upstream = uri.host + ":" + upstreamPort
                    )
                )
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

        adc.toxiproxyVersion ?: return

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
                podSpec.volumes = podSpec.volumes.prependIfNotNull(volume)
                dc.allNonSideCarContainers.overrideEnvVarsWithProxies(adc)
                podSpec.containers = podSpec.containers.prependIfNotNull(container)
            } else if (it.resource.kind == "Deployment") {
                // TODO: refactor
                modifyResource(it, "Added toxiproxy volume and sidecar container")
                val dc: Deployment = it.resource as Deployment
                val podSpec = dc.spec.template.spec
                podSpec.volumes = podSpec.volumes.prependIfNotNull(volume)
                dc.allNonSideCarContainers.overrideEnvVarsWithProxies(adc)
                podSpec.containers = podSpec.containers.prependIfNotNull(container)
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
                val portName = if (it.key == "http") "HTTP_PORT" else "${it.key}_HTTP_PORT".uppercase()
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
                    port = IntOrString(PortNumbers.TOXIPROXY_ADMIN_PORT)
                }
                initialDelaySeconds = 10
                timeoutSeconds = 1
            }
            args = listOf("-config", "$configPath/toxiproxy/config.json", "-host=0.0.0.0")
        }
    }

    fun List<Container>.overrideEnvVarsWithProxies(adc: AuroraDeploymentSpec) = this.forEach {
        adc
            .extractToxiproxyEndpoints()
            .map { (proxyName, varName) -> Pair(proxyName, it.env.find { v -> v.name == varName }) }
            .filterNot { (_, envVar) -> envVar == null }
            .forEach { (proxyName, envVar) ->
                envVar!!.value = UriComponentsBuilder
                    .fromUriString(envVar.value)
                    .host("localhost")
                    .port(toxiproxyConfigs.findPortByProxyName(proxyName))
                    .build()
                    .toUriString()
            }
    }
}
