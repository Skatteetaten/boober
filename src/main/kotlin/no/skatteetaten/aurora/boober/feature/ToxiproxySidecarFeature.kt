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
import io.fabric8.kubernetes.api.model.PodTemplateSpec
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.Secret
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
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.allNonSideCarContainers
import no.skatteetaten.aurora.boober.utils.boolean
import no.skatteetaten.aurora.boober.utils.notBlank
import org.apache.commons.codec.binary.Base64
import org.springframework.beans.factory.annotation.Value
import java.net.URI
import java.nio.charset.Charset

private const val FIRST_PORT_NUMBER = 18000 // The first Toxiproxy port will be set to this number

private const val TOXIPROXY_CONFIGS_CONTEXT_KEY = "toxiproxyConfigs"

val FeatureContext.toxiproxyConfigs: List<ToxiproxyConfig>
    get() = this.getContextKey(TOXIPROXY_CONFIGS_CONTEXT_KEY)

private const val SECRET_NAME_TO_PORT_MAP_CONTEXT_KEY = "secretNameToPortMap"

private val FeatureContext.secretNameToPortMap: Map<String, Int>
    get() = this.getContextKey(SECRET_NAME_TO_PORT_MAP_CONTEXT_KEY)

@org.springframework.stereotype.Service
class ToxiproxySidecarFeature(
    cantusService: CantusService,
    val databaseSchemaProvisioner: DatabaseSchemaProvisioner,
    val userDetailsProvider: UserDetailsProvider,
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

        val toxiproxyConfigs = mutableListOf(ToxiproxyConfig())

        // Variable for the port number that Toxiproxy will listen to
        // An addition of 1 to the value is made for each proxy
        var port = FIRST_PORT_NUMBER

        // Add endpoints to toxiproxyConfigs:
        spec.extractToxiproxyEndpoints().forEach { (proxyname, varname) ->
            val url = spec.fields["config/$varname"]?.value<String>()
            if (url != null) {
                val uri = URI(url)
                val upstreamPort = if (uri.port == -1) {
                    if (uri.scheme == "https") { PortNumbers.HTTPS_PORT } else { PortNumbers.HTTP_PORT }
                } else {
                    uri.port
                }
                toxiproxyConfigs.add(
                    ToxiproxyConfig(
                        name = proxyname,
                        listen = "0.0.0.0:$port",
                        upstream = uri.host + ":" + upstreamPort
                    )
                )
                port++
            }
        }

        // Add databases to toxiproxyConfigs:
        val secretNameToPortMap = mutableMapOf<String, Int>()
        val proxyAllDatabases = spec.fields["toxiproxy/database"]?.value?.booleanValue() == true
        findDatabases(spec)
            .filter {
                proxyAllDatabases ||
                    spec.fields["toxiproxy/database/" + it.name + "/enabled"]?.value?.booleanValue() == true
            }
            .createSchemaRequests(userDetailsProvider, spec)
            .associateWith { databaseSchemaProvisioner.findSchema(it) }
            .filterNot { it.value == null }
            .forEach { (request, schema) ->
                val proxyname = spec
                    .fields["toxiproxy/database/" + (request as SchemaForAppRequest).labels["name"] + "/proxyname"]
                    ?.value<String>()
                    ?: "database_" + schema!!.id
                toxiproxyConfigs.add(
                    ToxiproxyConfig(
                        name = proxyname,
                        listen = "0.0.0.0:$port",
                        upstream = schema!!.databaseInstance.host + ":" + schema.databaseInstance.port
                    )
                )
                secretNameToPortMap[request.getSecretName(prefix = spec.name)] = port
                port++
            }

        // Add servers and ports to toxiproxyConfigs:
        spec.extractToxiproxyServersAndPorts().forEach {
            val upstreamServer = spec.fields["config/${it.serverVar}"]?.value<String>()
            val upstreamPort = spec.fields["config/${it.portVar}"]?.value<String>()
            toxiproxyConfigs.add(
                ToxiproxyConfig(
                    name = it.proxyname,
                    listen = "0.0.0.0:$port",
                    upstream = "$upstreamServer:$upstreamPort"
                )
            )
            port++
        }

        return super.createContext(spec, cmd, validationContext) +
            createImageMetadataContext(repo = "shopify", name = "toxiproxy", tag = toxiProxyTag) +
            mapOf(
                TOXIPROXY_CONFIGS_CONTEXT_KEY to toxiproxyConfigs,
                SECRET_NAME_TO_PORT_MAP_CONTEXT_KEY to secretNameToPortMap
            )
    }

    override fun handlers(
        header: AuroraDeploymentSpec,
        cmd: AuroraContextCommand
    ): Set<AuroraConfigFieldHandler> = with(cmd.applicationFiles) {
        listOf(
            enumValues<ToxiproxyUrlSource>().flatMap { createToxiproxyFieldHandlers(it) },
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
            ),
            dbHandlers(cmd)
        ).flatten().toSet()
    }

    fun List<AuroraConfigFile>.createToxiproxyFieldHandlers(urlSource: ToxiproxyUrlSource): List<AuroraConfigFieldHandler> =
        listOf(AuroraConfigFieldHandler("toxiproxy/${urlSource.propName}")) +
            findSubKeysExpanded("toxiproxy/${urlSource.propName}").flatMap { endpointsOrDbOrS3 ->
                listOf(
                    AuroraConfigFieldHandler(
                        endpointsOrDbOrS3,
                        defaultValue = true,
                        validator = { it.boolean() },
                        canBeSimplifiedConfig = true
                    ),
                    AuroraConfigFieldHandler(
                        "$endpointsOrDbOrS3/proxyname",
                        defaultValue = generateProxyNameFromVarName(
                            findVarNameInFieldName(urlSource, endpointsOrDbOrS3),
                            urlSource
                        )
                    ),
                    AuroraConfigFieldHandler(
                        "$endpointsOrDbOrS3/enabled",
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
                missingOrInvalidEndpointVariableErrors(),
                missingServerAndPortVariableErrors(),
                missingDbErrors(),
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
            podSpec.volumes = podSpec.volumes.addIfNotNull(volume)
            dc.allNonSideCarContainers.overrideEnvVarsWithProxies(adc, context)
            podSpec.containers = podSpec.containers.addIfNotNull(container)
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
                "Secret" -> {
                    val secret: Secret = it.resource as Secret
                    val toxiproxyPort = context.secretNameToPortMap[secret.metadata.name]
                    if (toxiproxyPort != null) {
                        val newUrl = Base64
                            .decodeBase64(secret.data["jdbcurl"])
                            .toString(Charset.defaultCharset())
                            .convertToProxyUrl(toxiproxyPort)
                            .toByteArray()
                        secret.data["jdbcurl"] = Base64.encodeBase64String(newUrl)
                        modifyResource(it, "Changed JDBC URL to point to Toxiproxy")
                    }
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
        }
    }
}
