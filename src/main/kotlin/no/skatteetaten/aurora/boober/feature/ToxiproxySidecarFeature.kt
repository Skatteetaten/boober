// ktlint-disable indent

package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.configMap
import com.fkorotkov.kubernetes.exec
import com.fkorotkov.kubernetes.lifecycle
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newContainerPort
import com.fkorotkov.kubernetes.newProbe
import com.fkorotkov.kubernetes.newVolume
import com.fkorotkov.kubernetes.newVolumeMount
import com.fkorotkov.kubernetes.postStart
import com.fkorotkov.kubernetes.resources
import com.fkorotkov.kubernetes.tcpSocket
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.PodTemplateSpec
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
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.utils.allNonSideCarContainers
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.prependIfNotNull
import org.springframework.beans.factory.annotation.Value

private const val FIRST_PORT_NUMBER = 18000 // The first Toxiproxy port will be set to this number

private const val TOXIPROXY_CONFIGS_CONTEXT_KEY = "toxiproxyConfigs"

val FeatureContext.toxiproxyConfigs: List<ToxiproxyConfig>
    get() = this.getContextKey(TOXIPROXY_CONFIGS_CONTEXT_KEY)

@org.springframework.stereotype.Service
class ToxiproxySidecarFeature(
    cantusService: CantusService,
    @Value("\${toxiproxy.sidecar.default.version:2.1.3}") val sidecarVersion: String
) : AbstractResolveTagFeature(cantusService) {

    override fun isActive(spec: AuroraDeploymentSpec): Boolean = spec.toxiproxyVersion != null

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

        val appToxiproxyConfig = listOf(ToxiproxyConfig())
        val endpointsFromConfig = spec.endpointsFromConfig(FIRST_PORT_NUMBER)
        val nextPortNumber = endpointsFromConfig.getNextPortNumber(numberIfEmpty = FIRST_PORT_NUMBER)
        val serversAndPortsFromConfig = spec.serversAndPortsFromConfig(nextPortNumber)
        val toxiproxyConfigs = appToxiproxyConfig + endpointsFromConfig + serversAndPortsFromConfig

        return createImageMetadataContext(
            repo = "shopify",
            name = "toxiproxy",
            tag = toxiProxyTag
        ) + mapOf(TOXIPROXY_CONFIGS_CONTEXT_KEY to toxiproxyConfigs)
    }

    override fun handlers(
        header: AuroraDeploymentSpec,
        cmd: AuroraContextCommand
    ): Set<AuroraConfigFieldHandler> = with(cmd.applicationFiles) {
        listOf(
            findEndpointHandlers(),
            findServerAndPortHandlers(),
            findConfigFieldHandlers(),
            listOf(
                AuroraConfigFieldHandler(
                    "toxiproxy",
                    defaultValue = false,
                    validator = { it.boolean() },
                    canBeSimplifiedConfig = true
                ),
                AuroraConfigFieldHandler("toxiproxy/version", defaultValue = sidecarVersion),
                AuroraConfigFieldHandler("toxiproxy/endpointsFromConfig"),
                AuroraConfigFieldHandler("toxiproxy/serverAndPortFromConfig")
            )
        ).flatten().toSet()
    }

    fun List<AuroraConfigFile>.findEndpointHandlers(): List<AuroraConfigFieldHandler> =
        findSubKeysExpanded("toxiproxy/endpointsFromConfig").flatMap { endpoint ->
            listOf(
                AuroraConfigFieldHandler(
                    endpoint,
                    defaultValue = true,
                    validator = { it.boolean() },
                    canBeSimplifiedConfig = true
                ),
                AuroraConfigFieldHandler(
                    "$endpoint/proxyname",
                    defaultValue = generateProxyNameFromVarName(findVarNameInEndpointFieldName(endpoint))
                ),
                AuroraConfigFieldHandler(
                    "$endpoint/enabled",
                    defaultValue = true,
                    validator = { it.boolean() }
                ),
                AuroraConfigFieldHandler(
                    "$endpoint/initialEnabledState",
                    defaultValue = true,
                    validator = { it.boolean() }
                )
            )
        }

    fun List<AuroraConfigFile>.findServerAndPortHandlers(): List<AuroraConfigFieldHandler> =
        findSubKeysExpanded("toxiproxy/serverAndPortFromConfig").flatMap { proxyname ->
            listOf(
                AuroraConfigFieldHandler(proxyname),
                AuroraConfigFieldHandler(
                    "$proxyname/serverVariable",
                    validator = { it.notBlank("Server variable must be set") }
                ),
                AuroraConfigFieldHandler(
                    "$proxyname/portVariable",
                    validator = { it.notBlank("Port variable must be set") }
                ),
                AuroraConfigFieldHandler(
                    "$proxyname/initialEnabledState",
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
        with(adc) {
            listOf(
                missingEndpointVariableErrors(),
                missingServerAndPortVariableErrors(),
                proxynameDuplicateErrors()
            ).flatten()
        }
    } else { emptyList() }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {

        adc.toxiproxyVersion ?: return emptySet()

        val configMap = newConfigMap {
            metadata {
                name = "${adc.name}-toxiproxy-config"
                namespace = adc.namespace
            }
            data = mapOf("config.json" to jacksonObjectMapper().writeValueAsString(context.toxiproxyConfigs))
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

        val container = createToxiproxyContainer(adc, context)

        fun addToxiproxyVolumeAndSidecarContainer(auroraResource: AuroraResource, podTemplateSpec: PodTemplateSpec) {
            modifyResource(auroraResource, "Added toxiproxy volume and sidecar container")
            val dc = auroraResource.resource
            val podSpec = podTemplateSpec.spec
            podSpec.volumes = podSpec.volumes.prependIfNotNull(volume)
            dc.allNonSideCarContainers.overrideEnvVarsWithProxies(adc, context)
            podSpec.containers = podSpec.containers.prependIfNotNull(container)
        }

        resources.forEach {
            when (it.resource.kind) {
                "DeploymentConfig" -> {
                    val dc: DeploymentConfig = it.resource as DeploymentConfig
                    addToxiproxyVolumeAndSidecarContainer(it, dc.spec.template)
                }
                "Deployment" -> {
                    val dc: Deployment = it.resource as Deployment
                    addToxiproxyVolumeAndSidecarContainer(it, dc.spec.template)
                }
                "Service" -> {
                    val service: Service = it.resource as Service
                    service.spec.ports.filter { p -> p.name == "http" }.forEach { port ->
                        port.targetPort = IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT)
                    }
                    modifyResource(it, "Changed targetPort to point to toxiproxy")
                }
            }
        }
    }

    private fun createToxiproxyContainer(adc: AuroraDeploymentSpec, context: FeatureContext): Container {
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
            lifecycle {
                postStart {
                    exec {
                        command = listOf(
                            """
                                i=0;
                                s=1;
                                while [ ${"$"}i -ne 10 -a ${"$"}s -ne 0 ]; do
                                    i=$((${"$"}i+1));
                                    sleep 1;
                                    nc -zv 127.0.0.1 ${PortNumbers.TOXIPROXY_ADMIN_PORT};
                                    s=$?;
                                done;
                                if [ ${"$"}s -ne 0 ];
                                    then exit;
                                fi;
                            """.trim().replace(Regex("\\s+"), " ")
                        )
                    }
                }
            }
        }
    }
}
