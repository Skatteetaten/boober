package no.skatteetaten.aurora.boober.feature

import com.fkorotkov.kubernetes.httpGet
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newContainerPort
import com.fkorotkov.kubernetes.newProbe
import com.fkorotkov.kubernetes.resources
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
import no.skatteetaten.aurora.boober.utils.validUrl
import org.springframework.beans.factory.annotation.Value

const val JwtToStsConverterRoot = "azure/jwtToStsConverter"

val AuroraDeploymentSpec.isJwtToStsConverterEnabled: Boolean
    get() = this.getOrNull("$JwtToStsConverterRoot/enabled") ?: false

@org.springframework.stereotype.Service
class JwtToStsConverterFeature(
    cantusService: CantusService,
    @Value("\${clinger.sidecar.default.version:0.3.1}") val sidecarVersion: String
) : AbstractResolveTagFeature(cantusService) {
    override fun isActive(spec: AuroraDeploymentSpec): Boolean {
        return spec.isJwtToStsConverterEnabled
    }

    override fun enable(header: AuroraDeploymentSpec): Boolean {
        return !header.isJob
    }

    override fun createContext(
        spec: AuroraDeploymentSpec,
        cmd: AuroraContextCommand,
        validationContext: Boolean
    ): Map<String, Any> {

        val clingerTag = spec.getOrNull<String>("$JwtToStsConverterRoot/version")

        if (validationContext || clingerTag == null) {
            return emptyMap()
        }

        return createImageMetadataContext(
            repo = "no_skatteetaten_aurora",
            name = "clinger",
            tag = clingerTag
        )
    }

    override fun handlers(header: AuroraDeploymentSpec, cmd: AuroraContextCommand): Set<AuroraConfigFieldHandler> {
        return setOf(
            AuroraConfigFieldHandler(
                "$JwtToStsConverterRoot/enabled",
                defaultValue = false,
                validator = { it.boolean() }
            ),
            AuroraConfigFieldHandler(
                "$JwtToStsConverterRoot/version",
                defaultValue = sidecarVersion
            ),
            AuroraConfigFieldHandler(
                "$JwtToStsConverterRoot/discoveryUrl",
                validator = { it.validUrl(required = false) }),

            AuroraConfigFieldHandler(
                "$JwtToStsConverterRoot/ivGroupsRequired",
                defaultValue = false,
                validator = { it.boolean() })
        )
    }

    override fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext
    ) {

        if (!adc.isJwtToStsConverterEnabled) {
            return
        }

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
                    port.targetPort = IntOrString(PortNumbers.CLINGER_PROXY_SERVER_PORT)
                }

                modifyResource(it, "Changed targetPort to point to clinger")
            }
        }
    }

    private fun createClingerProxyContainer(adc: AuroraDeploymentSpec, context: FeatureContext): Container {
        val containerPorts = mapOf(
            "http" to PortNumbers.CLINGER_PROXY_SERVER_PORT,
            "management" to PortNumbers.CLINGER_MANAGEMENT_SERVER_PORT
        )

        val imageMetadata = context.imageMetadata

        return newContainer {
            name = "${adc.name}-clinger-mix" // Not naming it sidecar, as that will mask out secrets
            ports = containerPorts.map {
                newContainerPort {
                    name = it.key
                    containerPort = it.value
                    protocol = "TCP"
                }
            }

            env = (containerPorts.map {
                val portName = if (it.key == "http") "CLINGER_PROXY_SERVER_PORT" else "CLINGER_MANAGEMENT_SERVER_PORT"
                EnvVarBuilder().withName(portName).withValue(it.value.toString()).build()
            }).addIfNotNull(
                listOf(
                    EnvVarBuilder().withName("CLINGER_PROXY_BACKEND_HOST").withValue("0.0.0.0").build(),
                    EnvVarBuilder().withName("CLINGER_PROXY_BACKEND_PORT")
                        .withValue(PortNumbers.INTERNAL_HTTP_PORT.toString())
                        .build(),
                    EnvVarBuilder().withName("CLINGER_PROXY_SERVER_PORT")
                        .withValue(ports.first().containerPort.toString())
                        .build(),
                    EnvVarBuilder().withName("CLINGER_DISCOVERY_URL").withValue(adc["$JwtToStsConverterRoot/discoveryUrl"])
                        .build(),
                    EnvVarBuilder().withName("CLINGER_IV_GROUPS_REQUIRED")
                        .withValue(adc["$JwtToStsConverterRoot/ivGroupsRequired"])
                        .build()
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
            readinessProbe = newProbe {
                httpGet {
                    path = "/ready"
                    port = IntOrString(PortNumbers.CLINGER_MANAGEMENT_SERVER_PORT)
                }
                initialDelaySeconds = 10
                timeoutSeconds = 2
            }
            livenessProbe = newProbe {
                httpGet {
                    path = "/liveness"
                    port = IntOrString(PortNumbers.CLINGER_MANAGEMENT_SERVER_PORT)
                }
                initialDelaySeconds = 10
                timeoutSeconds = 2
            }
        }
    }
}
