package no.skatteetaten.aurora.boober.feature.azure

import com.fkorotkov.kubernetes.httpGet
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newContainerPort
import com.fkorotkov.kubernetes.newProbe
import com.fkorotkov.kubernetes.resources
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Quantity
import no.skatteetaten.aurora.boober.feature.FeatureContext
import no.skatteetaten.aurora.boober.feature.getContextKey
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.utils.addIfNotNull

val AuroraDeploymentSpec.isJwtToStsConverterEnabled: Boolean
    get() = this.getOrNull(JwtToStsConverterSubPart.ConfigPath.enabled) ?: false

// Copied from AbstractResolveTagFeature in order to avoid inheriting from Feature
private const val IMAGE_METADATA_CONTEXT_KEY = "imageMetadata"

// Copied from AbstractResolveTagFeature in order to avoid inheriting from Feature
internal val FeatureContext.imageMetadata: ImageMetadata
    get() = this.getContextKey(
        IMAGE_METADATA_CONTEXT_KEY
    )

class JwtToStsConverterSubPart {
    object ConfigPath {
        private const val root = "azure/jwtToStsConverter"
        const val enabled = "$root/enabled"
        const val version = "$root/version"
        const val discoveryUrl = "$root/discoveryUrl"
        const val ivGroupsRequired = "$root/ivGroupsRequired"
    }

    internal fun createClingerProxyContainer(adc: AuroraDeploymentSpec, context: FeatureContext): Container {
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
                        .build()
                )
            ).addIfNotNull(createEnvOrNull("CLINGER_DISCOVERY_URL", adc.getOrNull<String>(ConfigPath.discoveryUrl))
            ).addIfNotNull(
                createEnvOrNull("CLINGER_IV_GROUPS_REQUIRED", adc.getOrNull<String>(ConfigPath.ivGroupsRequired)))

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

    private fun createEnvOrNull(key: String, value: String?): EnvVar? {
        return if (value == null) {
            null
        } else {
            EnvVarBuilder().withName(key).withValue(value).build()
        }
    }
}
