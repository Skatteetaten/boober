package no.skatteetaten.aurora.boober.feature.azure

import com.fkorotkov.kubernetes.httpGet
import com.fkorotkov.kubernetes.newContainer
import com.fkorotkov.kubernetes.newContainerPort
import com.fkorotkov.kubernetes.newProbe
import com.fkorotkov.kubernetes.resources
import io.fabric8.kubernetes.api.model.Container
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.EnvVarSource
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.ObjectFieldSelector
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.SecretKeySelector
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.feature.FeatureContext
import no.skatteetaten.aurora.boober.feature.getContextKey
import no.skatteetaten.aurora.boober.feature.name
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldHandler
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.utils.addIf
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.validUrl

val AuroraDeploymentSpec.isJwtToStsConverterEnabled: Boolean
    get() = this.getOrNull(JwtToStsConverterSubPart.ConfigPath.enabled) ?: false

val AuroraDeploymentSpec.isIvGroupsEnabled: Boolean
    get() = (this.getOrNull(JwtToStsConverterSubPart.ConfigPath.ivGroupsRequired) ?: false) &&
        this.getOrNull<String>(JwtToStsConverterSubPart.ConfigPath.ldapUrl) != null &&
        this.getOrNull<String>(JwtToStsConverterSubPart.ConfigPath.ldapUserSecretRef) != null

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
        const val ldapUserSecretRef = "$root/ldapUserSecretRef"
        const val ldapUrl = "$root/ldapUrl"
    }

    fun modify(
        adc: AuroraDeploymentSpec,
        resources: Set<AuroraResource>,
        context: FeatureContext,
        parent: Feature
    ) {
        if (!adc.isJwtToStsConverterEnabled) {
            return
        }

        val container = createClingerProxyContainer(adc, context)
        resources.forEach {
            if (it.resource.kind == "DeploymentConfig") {
                parent.modifyResource(it, "Added clinger sidecar container")
                val dc: DeploymentConfig = it.resource as DeploymentConfig
                val podSpec = dc.spec.template.spec
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            } else if (it.resource.kind == "Deployment") {
                parent.modifyResource(it, "Added clinger sidecar container")
                val dc: Deployment = it.resource as Deployment
                val podSpec = dc.spec.template.spec
                podSpec.containers = podSpec.containers.addIfNotNull(container)
            } else if (it.resource.kind == "Service") {
                val service: Service = it.resource as Service
                service.spec.ports.filter { p -> p.name == "http" }.forEach { port ->
                    port.targetPort = IntOrString(PortNumbers.CLINGER_PROXY_SERVER_PORT)
                }

                parent.modifyResource(it, "Changed targetPort to point to clinger")
            } else if (it.resource.kind == "Secret") {
                if (adc.isIvGroupsEnabled && it.resource.metadata.name == adc.getOrNull(ConfigPath.ldapUserSecretRef)) {
                    parent.modifyResource(it, "added LDAP secret ref from ${it.resource.metadata.name}")
                }
            }
        }
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

            createEnvForContainer(containerPorts, adc)

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

    private fun Container.createEnvForContainer(
        containerPorts: Map<String, Int>,
        adc: AuroraDeploymentSpec
    ) {
        val websealEnabled = adc.getOrNull<String>("webseal")?.let {
            adc.featureEnabled("webseal") { "true" } ?: "false"
        }

        env = (
            containerPorts.map {
                val portName = if (it.key == "http") "CLINGER_PROXY_SERVER_PORT" else "CLINGER_MANAGEMENT_SERVER_PORT"
                EnvVarBuilder().withName(portName).withValue(it.value.toString()).build()
            }
            ).addIfNotNull(
            listOf(
                EnvVarBuilder().withName("CLINGER_PROXY_BACKEND_HOST").withValue("0.0.0.0").build(),
                EnvVarBuilder().withName("CLINGER_PROXY_BACKEND_PORT")
                    .withValue(PortNumbers.INTERNAL_HTTP_PORT.toString())
                    .build(),
                EnvVarBuilder().withName("CLINGER_PROXY_SERVER_PORT")
                    .withValue(ports.first().containerPort.toString())
                    .build(),
                EnvVarBuilder().withName("CLINGER_AURORAAZUREAPP_NAME")
                    .withValue(adc.name)
                    .build(),
                EnvVarBuilder().withName("CLINGER_WEBSEAL_TRAFFIC_ACCEPTED")
                    .withValue(websealEnabled ?: "false")
                    .build(),
                createEnvRef(name = "POD_NAMESPACE", apiVersion = "v1", fieldPath = "metadata.namespace"),
                createEnvRef(name = "POD_NAME", apiVersion = "v1", fieldPath = "metadata.name")
            )
        ).addIfNotNull(
            createEnvOrNull("CLINGER_DISCOVERY_URL", adc.getOrNull<String>(ConfigPath.discoveryUrl))
        ).addIfNotNull(
            createEnvOrNull("CLINGER_IV_GROUPS_REQUIRED", adc.getOrNull<String>(ConfigPath.ivGroupsRequired))
        ).addIf(
            adc.isIvGroupsEnabled,
            listOf(
                createSecretRef("CLINGER_LDAP_USERNAME", adc[ConfigPath.ldapUserSecretRef], "ldap.username"),
                createSecretRef("CLINGER_LDAP_PASSWORD", adc[ConfigPath.ldapUserSecretRef], "ldap.password"),
                createEnvOrNull("CLINGER_LDAP_ADDRESS", adc[ConfigPath.ldapUrl])
            )
        )
    }

    private fun createEnvOrNull(name: String, value: String?): EnvVar? {
        return if (value == null) {
            null
        } else {
            EnvVarBuilder().withName(name).withValue(value).build()
        }
    }

    private fun createEnvRef(name: String, apiVersion: String, fieldPath: String): EnvVar {
        return EnvVarBuilder().withName(name)
            .withValueFrom(
                EnvVarSource(null, ObjectFieldSelector(apiVersion, fieldPath), null, null)
            )
            .build()
    }

    private fun createSecretRef(name: String, secretName: String, parameterInFile: String): EnvVar {

        return EnvVarBuilder().withName(name)
            .withValueFrom(
                EnvVarSource(
                    null, null, null,
                    SecretKeySelector(
                        parameterInFile, secretName, false
                    )
                )
            )
            .build()
    }

    fun handlers(sidecarVersion: String, defaultLdapUrl: String): Set<AuroraConfigFieldHandler> =
        setOf(
            AuroraConfigFieldHandler(
                ConfigPath.enabled,
                defaultValue = false,
                validator = { it.boolean(required = false) }
            ),
            AuroraConfigFieldHandler(
                ConfigPath.version,
                defaultValue = sidecarVersion
            ),
            AuroraConfigFieldHandler(
                ConfigPath.discoveryUrl,
                validator = { it.validUrl(required = false) }
            ),

            AuroraConfigFieldHandler(
                ConfigPath.ivGroupsRequired,
                defaultValue = false,
                validator = { it.boolean() }
            ),
            AuroraConfigFieldHandler(
                ConfigPath.ldapUserSecretRef,
                defaultValue = ""
            ),
            AuroraConfigFieldHandler(
                ConfigPath.ldapUrl,
                defaultValue = defaultLdapUrl,
                validator = { it.validUrl(required = false) }
            )
        )
}
