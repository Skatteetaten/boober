package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fkorotkov.kubernetes.envVar
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.namespace
import com.fkorotkov.kubernetes.service
import com.fkorotkov.kubernetes.servicePort
import com.fkorotkov.kubernetes.spec
import com.fkorotkov.openshift.buildConfig
import com.fkorotkov.openshift.buildTriggerPolicy
import com.fkorotkov.openshift.customStrategy
import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.imageChange
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.output
import com.fkorotkov.openshift.projectRequest
import com.fkorotkov.openshift.route
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.strategy
import com.fkorotkov.openshift.to
import io.fabric8.kubernetes.api.model.IntOrString
import no.skatteetaten.aurora.boober.Boober
import no.skatteetaten.aurora.boober.mapper.platform.AuroraServicePort
import no.skatteetaten.aurora.boober.mapper.v1.PortNumbers
import no.skatteetaten.aurora.boober.mapper.v1.ToxiProxyDefaults
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType.ConfigMap
import no.skatteetaten.aurora.boober.model.MountType.PVC
import no.skatteetaten.aurora.boober.model.MountType.Secret
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.ConfigMapGenerator
import no.skatteetaten.aurora.boober.service.internal.ContainerGenerator
import no.skatteetaten.aurora.boober.service.internal.DbhSecretGenerator
import no.skatteetaten.aurora.boober.service.internal.DeploymentConfigGenerator
import no.skatteetaten.aurora.boober.service.internal.ImageStreamGenerator
import no.skatteetaten.aurora.boober.service.internal.RolebindingGenerator
import no.skatteetaten.aurora.boober.service.internal.SecretGenerator
import no.skatteetaten.aurora.boober.service.internal.findAndCreateMounts
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.Instants.now
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class OpenShiftObjectGenerator(
    @Value("\${boober.docker.registry}") val dockerRegistry: String,
    val openShiftObjectLabelService: OpenShiftObjectLabelService,
    val mapper: ObjectMapper,
    val openShiftTemplateProcessor: OpenShiftTemplateProcessor,
    val openShiftClient: OpenShiftResourceClient) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftObjectGenerator::class.java)

    fun generateDeploymentRequest(name: String): JsonNode {

        val deploymentRequest = mapOf(
            "kind" to "DeploymentRequest",
            "apiVersion" to "v1",
            "name" to name,
            "latest" to true,
            "force" to true
        )

        return mapper.convertValue(deploymentRequest)
    }

    fun generateApplicationObjects(deployId: String, auroraDeploymentSpec: AuroraDeploymentSpec,
                                   provisioningResult: ProvisioningResult? = null): List<JsonNode> {

        return withLabelsAndMounts(deployId, auroraDeploymentSpec, provisioningResult, { labels, mounts ->

            listOf<JsonNode>()
                .addIfNotNull(generateDeploymentConfig(auroraDeploymentSpec, labels, mounts))
                .addIfNotNull(generateService(auroraDeploymentSpec, labels))
                .addIfNotNull(generateImageStream(deployId, auroraDeploymentSpec))
                .addIfNotNull(generateBuilds(auroraDeploymentSpec, deployId))
                .addIfNotNull(generateSecretsAndConfigMaps(auroraDeploymentSpec.name, mounts
                    ?: emptyList(), labels, provisioningResult))
                .addIfNotNull(generateRoute(auroraDeploymentSpec, labels))
                .addIfNotNull(generateTemplate(auroraDeploymentSpec))
                .addIfNotNull(generateLocalTemplate(auroraDeploymentSpec))
        })
    }

    fun generateProjectRequest(environment: AuroraDeployEnvironment): JsonNode {

        val projectRequest = projectRequest {
            apiVersion = "v1"
            metadata {
                name = environment.namespace
                finalizers = null
                ownerReferences = null
            }
        }

        return mapper.convertValue(projectRequest)
    }

    fun generateNamespace(environment: AuroraDeployEnvironment): JsonNode {

        val namespace = namespace {
            apiVersion = "v1"
            metadata {
                val ttl = environment.ttl?.let {
                    val removeInstant = now + it
                    "removeAfter" to removeInstant.epochSecond.toString()
                }
                labels = mapOf("affiliation" to environment.affiliation).addIfNotNull(ttl)
                name = environment.namespace
                finalizers = null
                ownerReferences = null
            }
        }

        return mapper.convertValue(namespace)
    }

    fun generateRolebindings(permissions: Permissions): List<JsonNode> {

        val admin = RolebindingGenerator.create("admin", permissions.admin)

        val view = permissions.view?.let {
            RolebindingGenerator.create("view", it)
        }

        return listOf(admin).addIfNotNull(view)
            .map { mapper.convertValue<JsonNode>(it) }
    }

    fun generateDeploymentConfig(deployId: String, deploymentSpec: AuroraDeploymentSpec, provisioningResult: ProvisioningResult? = null): JsonNode? =
        withLabelsAndMounts(deployId, deploymentSpec, provisioningResult) { labels, mounts ->
            generateDeploymentConfig(deploymentSpec, labels, mounts)
        }

    fun generateDeploymentConfig(auroraDeploymentSpec: AuroraDeploymentSpec,
                                 labels: Map<String, String>,
                                 mounts: List<Mount>?): JsonNode? {

        if (auroraDeploymentSpec.deploy == null) {
            return null
        }

        val applicationPlatformHandler = Boober.APPLICATION_PLATFORM_HANDLERS[auroraDeploymentSpec.applicationPlatform]
            ?: throw IllegalArgumentException("ApplicationPlatformHandler ${auroraDeploymentSpec.applicationPlatform} is not present")

        val deployment = applicationPlatformHandler.handleAuroraDeployment(auroraDeploymentSpec, labels, mounts)

        val containers = deployment.containers.map { ContainerGenerator.create(it) }

        val dc = DeploymentConfigGenerator.create(deployment, containers)

        return mapper.convertValue(dc)
    }

    fun generateService(auroraDeploymentSpec: AuroraDeploymentSpec, serviceLabels: Map<String, String>): JsonNode? {
        return auroraDeploymentSpec.deploy?.let {

            val webseal = it.webseal?.let {
                val host = it.host ?: "${auroraDeploymentSpec.name}-${auroraDeploymentSpec.environment.namespace}"
                "sprocket.sits.no/service.webseal" to host
            }

            val websealRoles = it.webseal?.roles?.let {
                "sprocket.sits.no/service.webseal-roles" to it
            }

            val prometheusAnnotations = it.prometheus?.takeIf { it.path != "" }?.let {
                mapOf("prometheus.io/scheme" to "http",
                    "prometheus.io/scrape" to "true",
                    "prometheus.io/path" to it.path,
                    "prometheus.io/port" to "${it.port}"
                )
            } ?: mapOf("prometheus.io/scrape" to "false")

            val auroraServicePorts = auroraDeploymentSpec.deploy.toxiProxy?.let {
                listOf(AuroraServicePort(name = "http", port = PortNumbers.HTTP_PORT, targetPort = PortNumbers.TOXIPROXY_HTTP_PORT),
                    AuroraServicePort(name = "management", port = PortNumbers.TOXIPROXY_ADMIN_PORT, targetPort = PortNumbers.TOXIPROXY_ADMIN_PORT))
            } ?: listOf(AuroraServicePort("http", port = PortNumbers.HTTP_PORT, targetPort = PortNumbers.INTERNAL_HTTP_PORT))

            val servicePorts = auroraServicePorts.map {
                servicePort {
                    name = it.name
                    protocol = it.protocol
                    port = it.port
                    targetPort = IntOrString(it.targetPort)
                    nodePort = it.nodePort
                }
            }

            val service = service {
                apiVersion = "v1"
                metadata {
                    name = auroraDeploymentSpec.name
                    finalizers = null
                    ownerReferences = null
                    annotations = prometheusAnnotations.addIfNotNull(webseal)
                        .addIfNotNull(websealRoles)
                    labels = serviceLabels
                }

                spec {
                    ports = servicePorts
                    selector = mapOf("name" to auroraDeploymentSpec.name)
                    type = "ClusterIP"
                    sessionAffinity = "None"
                }
            }
            mapper.convertValue(service)

        }
    }

    fun generateImageStream(deployId: String, auroraDeploymentSpec: AuroraDeploymentSpec): JsonNode? {
        return auroraDeploymentSpec.deploy?.let {

            val labels = openShiftObjectLabelService.createCommonLabels(auroraDeploymentSpec, deployId,
                mapOf("releasedVersion" to it.version))

            val imageStream = if (auroraDeploymentSpec.type == TemplateType.development) {
                ImageStreamGenerator.createLocalImageStream(auroraDeploymentSpec.name, labels)
            } else {
                ImageStreamGenerator.createRemoteImageStream(auroraDeploymentSpec.name, labels, dockerRegistry, it.dockerImagePath, it.dockerTag)
            }

            return mapper.convertValue(imageStream)
        }
    }

    fun generateLocalTemplate(auroraDeploymentSpec: AuroraDeploymentSpec): List<JsonNode>? {
        return auroraDeploymentSpec.localTemplate?.let {
            openShiftTemplateProcessor.generateObjects(it.templateJson as ObjectNode, it.parameters, auroraDeploymentSpec)
        }
    }

    fun generateTemplate(auroraDeploymentSpec: AuroraDeploymentSpec): List<JsonNode>? {
        return auroraDeploymentSpec.template?.let {
            val template = openShiftClient.get("template", "openshift", it.template)?.body as ObjectNode
            openShiftTemplateProcessor.generateObjects(template, it.parameters, auroraDeploymentSpec)
        }
    }

    fun generateRoute(auroraDeploymentSpec: AuroraDeploymentSpec, routeLabels: Map<String, String>): List<JsonNode>? {
        return auroraDeploymentSpec.route?.route?.map {

            val route = route {
                apiVersion = "v1"
                metadata {
                    name = it.name
                    labels = routeLabels
                    ownerReferences = null
                    finalizers = null
                    it.annotations?.let {
                        annotations = it.mapKeys { it.key.replace("|", "/") }
                    }
                }
                spec {
                    to {
                        kind = "Service"
                        name = auroraDeploymentSpec.name
                    }
                    host = auroraDeploymentSpec.assembleRouteHost(it.host ?: auroraDeploymentSpec.name)
                    it.path?.let {
                        path = it
                    }
                }

            }
            mapper.convertValue<JsonNode>(route)
        }
    }

    fun generateSecretsAndConfigMapsInTest(deployId: String, deploymentSpec: AuroraDeploymentSpec, provisioningResult: ProvisioningResult? = null, name: String): List<JsonNode>? {

        return withLabelsAndMounts(deployId, deploymentSpec, provisioningResult, { labels, mounts ->
            generateSecretsAndConfigMaps(name, mounts ?: emptyList(), labels, provisioningResult)
        })
    }

    private fun generateSecretsAndConfigMaps(appName: String, mounts: List<Mount>, labels: Map<String, String>, provisioningResult: ProvisioningResult?): List<JsonNode> {

        val schemaSecrets = provisioningResult?.schemaProvisionResults
            ?.let { DbhSecretGenerator.create(appName, it, labels) }
            ?: emptyList()

        val schemaSecretNames = schemaSecrets.map { it.metadata.name }

        return mounts
            .filter { !it.exist }
            .filter { !schemaSecretNames.contains(it.volumeName) }
            .mapNotNull { mount: Mount ->
                when (mount.type) {
                    ConfigMap -> mount.content
                        ?.let { ConfigMapGenerator.create(mount.getNamespacedVolumeName(appName), labels, it) }
                    Secret -> mount.secretVaultName
                        ?.let { provisioningResult?.vaultResults?.getVaultData(it) }
                        ?.let { SecretGenerator.create(mount.getNamespacedVolumeName(appName), labels, it) }
                    PVC -> null
                }
            }
            .plus(schemaSecrets)
            .map { mapper.convertValue<JsonNode>(it) }
    }

    private fun generateBuilds(deploymentSpec: AuroraDeploymentSpec, deployId: String): List<JsonNode>? {
        return deploymentSpec.build?.let {
            val buildName = if (it.buildSuffix != null) {
                "${deploymentSpec.name}-${it.buildSuffix}"
            } else {
                deploymentSpec.name
            }

            val build = buildConfig {
                apiVersion = "v1"
                metadata {
                    name = buildName
                    labels = openShiftObjectLabelService.createCommonLabels(deploymentSpec, deployId, name = buildName)
                    ownerReferences = null
                    finalizers = null
                }

                spec {
                    if (it.triggers) {
                        triggers = listOf(
                            buildTriggerPolicy {
                                type = "ImageChange"
                                imageChange {
                                    from {
                                        kind = "ImageStreamTag"
                                        namespace = "openshift"
                                        name = "${it.baseName}:${it.baseVersion}"
                                    }
                                }
                            },
                            buildTriggerPolicy {
                                type = "ImageChange"
                                imageChange {

                                }
                            }
                        )
                    }
                    strategy {
                        type = "Custom"
                        customStrategy {
                            from {
                                kind = "ImageStreamTag"
                                namespace = "openshift"
                                name = "${it.builderName}:${it.builderVersion}"
                            }

                            val envMap = mapOf(
                                "ARTIFACT_ID" to it.artifactId,
                                "GROUP_ID" to it.groupId,
                                "VERSION" to it.version,
                                "DOCKER_BASE_VERSION" to it.baseVersion,
                                "DOCKER_BASE_IMAGE" to "aurora/${it.baseName}",
                                "PUSH_EXTRA_TAGS" to it.extraTags
                            )

                            env = envMap.map {
                                envVar {
                                    name = it.key
                                    value = it.value
                                }
                            }

                            if (it.applicationPlatform == "web") {
                                env = env + envVar {
                                    name = "APPLICATION_TYPE"
                                    value = "nodejs"
                                }
                            }

                            exposeDockerSocket = true
                        }
                        output {
                            imageLabels = null
                            to {
                                kind = it.outputKind
                                if (it.outputKind == "DockerImage") {
                                    name = "$dockerRegistry/${it.outputName}"
                                } else {
                                    name = it.outputName
                                }
                            }
                        }
                    }
                }
            }
            val bc = mapper.convertValue<JsonNode>(build)
            //TODO: Handle jenkinsfile buildConfig
            listOf(bc)
        }
    }

    private fun <T> withLabelsAndMounts(deployId: String, deploymentSpec: AuroraDeploymentSpec,
                                        provisioningResult: ProvisioningResult? = null,
                                        c: (labels: Map<String, String>, mounts: List<Mount>?) -> T): T {

        val mounts: List<Mount> = findAndCreateMounts(deploymentSpec, provisioningResult)
        val labels = openShiftObjectLabelService.createCommonLabels(deploymentSpec, deployId)
        return c(labels, mounts)
    }
}