package no.skatteetaten.aurora.boober.feature

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newConfigMap
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newContainerPort
import com.fkorotkov.kubernetes.newProbe
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
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.notBlank
import no.skatteetaten.aurora.boober.utils.validUrl

// val AuroraDeploymentSpec.clingerSidecar: String? get() = this.getOrNull<String>("azure/proxySidecar")
val AuroraDeploymentSpec.clingerSidecar: String?
    get() =
        this.featureEnabled("azure/proxySidecar") {
            this["azure/proxySidecar/version"]
        }

@org.springframework.stereotype.Service
class ClingerSidecarFeature(
    cantusService: CantusService
) : AbstractResolveTagFeature(cantusService) {
    override fun isActive(spec: AuroraDeploymentSpec): Boolean {
        val clingerSidecar = spec.clingerSidecar

        return clingerSidecar != null
    }

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {

        val clingerTag = spec.clingerSidecar

        if (validationContext || clingerTag == null) {
            return emptyMap()
        }

        return createImageMetadataContext(
            repo = "shopify",
            name = "clinger",
            tag = clingerTag
        )
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                "azure",
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler(
                "azure/proxySidecar",
                defaultValue = false,
                validator = { it.boolean() },
                canBeSimplifiedConfig = true
            ),
            AuroraConfigFieldHandler(
                "azure/proxySidecar/version",
                defaultValue = "0.1.0",
                validator = { it.notBlank("Expecting clinger version or false") }),
            AuroraConfigFieldHandler(
                "azure/proxySidecar/discoveryUrl",
                validator = { it.validUrl(required = false) }),

            AuroraConfigFieldHandler(
                "azure/proxySidecar/ivGroupsRequired",
                defaultValue = false,
                validator = { it.boolean() })
        )
    }

    override fun generate(adc: AuroraDeploymentSpec, context: FeatureContext): Set<AuroraResource> {

        adc.clingerSidecar ?: return emptySet()

        val configMap = newConfigMap {
            metadata {
                name = "${adc.name}-clinger-config"
                namespace = adc.namespace
            }
            data = emptyMap() // mapOf("config.json" to getClingerProxyConfig())
        }
        return setOf(generateResource(configMap))
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {

        adc.clingerSidecar ?: return

        val container = createClingerProxyContainer(adc, context)
        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                modifyResource(it, "Added clinger sidecar container")
                val dc: DeploymentConfig = it.resource as DeploymentConfig
                val podSpec = dc.spec.template.spec
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            } else if (it.resource.kind == "Deployment") {
                modifyResource(it, "Added clinger sidecar container")
                val dc: Deployment = it.resource as Deployment
                val podSpec = dc.spec.template.spec
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            } else if (it.resource.kind == "Service") {
                val service: Service = it.resource as Service
                service.spec.ports.filter { p -> p.name == "http" }.forEach { port ->
                    port.targetPort = IntOrString(PortNumbers.CLINGER_PROXY_HTTP_PORT)
                }

                modifyResource(it, "Changed targetPort to point to clinger")
            }
        }
    }

    private fun createClingerProxyContainer(adc: AuroraDeploymentSpec, context: FeatureContext): Container {
        val containerPorts = mapOf(
            "http" to PortNumbers.CLINGER_PROXY_HTTP_PORT,
            "management" to PortNumbers.CLINGER_PROXY_ADMIN_HTTP_PORT
        )

        val imageMetadata = context.imageMetadata

        return newContainer {
            name = "${adc.name}-clinger-sidecar"
            ports = containerPorts.map {
                newContainerPort {
                    name = it.key
                    containerPort = it.value
                    protocol = "TCP"
                }
            }
            env = (containerPorts.map {
                val portName = if (it.key == "http") "HTTP_PORT" else "${it.key}_HTTP_PORT".toUpperCase()
                EnvVarBuilder().withName(portName).withValue(it.value.toString()).build()
            }).addIfNotNull(
                listOf(
                    EnvVarBuilder().withName("PROXY_BACKEND_HOST").withValue("0.0.0.0").build(),
                    EnvVarBuilder().withName("PROXY_BACKEND_PORT").withValue(ports.first().containerPort.toString())
                        .build(),
                    EnvVarBuilder().withName("DISCOVERY_URL").withValue(adc["azure/proxySidecar/discoveryUrl"]).build(),
                    EnvVarBuilder().withName("IV_GROUPS_REQUIRED").withValue(adc["azure/proxySidecar/ivGroupsRequired"])
                        .build(),
                    EnvVarBuilder().withName("APPID").withValue("presently-just-fake").build()
                )
            )

            resources {
                limits = mapOf(
                    "memory" to Quantity("256Mi"),
                    "cpu" to Quantity("1")
                )
                requests = mapOf(
                    "memory" to Quantity("128Mi"),
                    "cpu" to Quantity("25m")
                )
            }
            image = imageMetadata.getFullImagePath()
            // TODO use actual readiness endpoint, and add liveness
            readinessProbe = newProbe {
                tcpSocket {
                    port = IntOrString(PortNumbers.CLINGER_PROXY_HTTP_PORT)
                }
                initialDelaySeconds = 10
                timeoutSeconds = 1
            }
        }
    }

    /**
     * TODO Consider to make proxy object more generic. As it is it is stolen from ToxiProxy
     * @see ToxiproxySidecarFeature#getToxiProxyConfig
     */
    data class ClingerProxyConfig(val name: String, val listen: String, val upstream: String)

    fun getClingerProxyConfig(): String {
        val config = ClingerProxyConfig(
            name = "app",
            listen = "0.0.0.0:" + PortNumbers.CLINGER_PROXY_HTTP_PORT,
            upstream = "0.0.0.0:" + PortNumbers.INTERNAL_HTTP_PORT
        )

        return jacksonObjectMapper().writeValueAsString(listOf(config))
    }
}
