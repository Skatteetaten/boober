package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.kubernetes.metadata
import com.fkorotkov.kubernetes.newNamespace
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newProjectRequest
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.mapper.platform.createEnvVars
import no.skatteetaten.aurora.boober.mapper.platform.podVolumes
import no.skatteetaten.aurora.boober.mapper.platform.volumeMount
import no.skatteetaten.aurora.boober.mapper.v1.ToxiProxyDefaults
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType.ConfigMap
import no.skatteetaten.aurora.boober.model.MountType.PVC
import no.skatteetaten.aurora.boober.model.MountType.Secret
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.BuildConfigGenerator
import no.skatteetaten.aurora.boober.service.internal.ConfigMapGenerator
import no.skatteetaten.aurora.boober.service.internal.ContainerGenerator
import no.skatteetaten.aurora.boober.service.internal.DbhSecretGenerator
import no.skatteetaten.aurora.boober.service.internal.DeploymentConfigGenerator
import no.skatteetaten.aurora.boober.service.internal.ImageStreamGenerator
import no.skatteetaten.aurora.boober.service.internal.RolebindingGenerator
import no.skatteetaten.aurora.boober.service.internal.RouteGenerator
import no.skatteetaten.aurora.boober.service.internal.SecretGenerator
import no.skatteetaten.aurora.boober.service.internal.ServiceGenerator
import no.skatteetaten.aurora.boober.service.internal.StsSecretGenerator
import no.skatteetaten.aurora.boober.service.internal.findAndCreateMounts
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.Instants.now
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class OpenShiftObjectGenerator(
    @Value("\${boober.docker.registry}") val dockerRegistry: String,
    val openShiftObjectLabelService: OpenShiftObjectLabelService,
    val mapper: ObjectMapper,
    val openShiftTemplateProcessor: OpenShiftTemplateProcessor,
    val openShiftClient: OpenShiftResourceClient,
    @Value("\${boober.route.suffix}") val routeSuffix: String
) {

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

    fun generateApplicationObjects(
        deployId: String,
        auroraDeploymentSpecInternal: AuroraDeploymentSpecInternal,
        provisioningResult: ProvisioningResult? = null,
        ownerReference: OwnerReference
    ): List<JsonNode> {

        return withLabelsAndMounts(deployId, auroraDeploymentSpecInternal, provisioningResult) { labels, mounts ->

            listOf<JsonNode>()
                .addIfNotNull(generateDeploymentConfig(auroraDeploymentSpecInternal, labels, mounts, ownerReference))
                .addIfNotNull(
                    generateService(
                        auroraDeploymentSpecInternal,
                        labels + ("name" to auroraDeploymentSpecInternal.name),
                        ownerReference
                    )
                )
                .addIfNotNull(generateImageStream(deployId, auroraDeploymentSpecInternal, ownerReference))
                .addIfNotNull(generateBuilds(auroraDeploymentSpecInternal, deployId, ownerReference))
                .addIfNotNull(
                    generateSecretsAndConfigMaps(
                        appName = auroraDeploymentSpecInternal.name,
                        mounts = mounts ?: emptyList(),
                        labels = labels,
                        provisioningResult = provisioningResult
                        ,
                        ownerReference = ownerReference
                    )
                )
                .addIfNotNull(generateRoute(auroraDeploymentSpecInternal, labels, ownerReference))
                .addIfNotNull(generateTemplates(auroraDeploymentSpecInternal, mounts, ownerReference))
        }
    }

    fun generateProjectRequest(environment: AuroraDeployEnvironment): JsonNode {

        val projectRequest = newProjectRequest {
            apiVersion = "v1"
            metadata {
                name = environment.namespace
            }
        }

        return mapper.convertValue(projectRequest)
    }

    fun generateNamespace(environment: AuroraDeployEnvironment): JsonNode {

        val namespace = newNamespace {
            apiVersion = "v1"
            metadata {
                val ttl = environment.ttl?.let {
                    val removeInstant = now + it
                    "removeAfter" to removeInstant.epochSecond.toString()
                }
                labels = mapOf("affiliation" to environment.affiliation).addIfNotNull(ttl)
                name = environment.namespace
            }
        }

        return mapper.convertValue(namespace)
    }

    fun generateRolebindings(permissions: Permissions): List<JsonNode> {

        val admin = RolebindingGenerator.create("admin", permissions.admin)

        val view = permissions.view?.let {
            RolebindingGenerator.create("view", it)
        }

        return listOf(admin).addIfNotNull(view).map { mapper.convertValue<JsonNode>(it) }
    }

    fun generateDeploymentConfig(
        deployId: String,
        deploymentSpecInternal: AuroraDeploymentSpecInternal,
        provisioningResult: ProvisioningResult? = null
        ,
        ownerReference: OwnerReference
    ): JsonNode? =
        withLabelsAndMounts(deployId, deploymentSpecInternal, provisioningResult) { labels, mounts ->
            generateDeploymentConfig(deploymentSpecInternal, labels, mounts, ownerReference)
        }

    fun generateDeploymentConfig(
        auroraDeploymentSpecInternal: AuroraDeploymentSpecInternal,
        labels: Map<String, String>,
        mounts: List<Mount>?,
        ownerReference: OwnerReference
    ): JsonNode? {

        if (auroraDeploymentSpecInternal.deploy == null) {
            return null
        }

        val applicationPlatformHandler =
            AuroraDeploymentSpecService.APPLICATION_PLATFORM_HANDLERS[auroraDeploymentSpecInternal.applicationPlatform]
                ?: throw IllegalArgumentException("ApplicationPlatformHandler ${auroraDeploymentSpecInternal.applicationPlatform} is not present")

        val sidecarContainers = applicationPlatformHandler.createSidecarContainers(
            auroraDeploymentSpecInternal,
            mounts?.filter { it.targetContainer == ToxiProxyDefaults.NAME })

        val deployment = applicationPlatformHandler.handleAuroraDeployment(
            auroraDeploymentSpecInternal,
            labels,
            mounts,
            routeSuffix,
            sidecarContainers
        )

        val containers = deployment.containers.map { ContainerGenerator.create(it) }

        val dc = DeploymentConfigGenerator.create(deployment, containers, ownerReference)

        return mapper.convertValue(dc)
    }

    fun generateService(
        auroraDeploymentSpecInternal: AuroraDeploymentSpecInternal,
        serviceLabels: Map<String, String>,
        reference: OwnerReference
    ): JsonNode? {
        return ServiceGenerator.generateService(auroraDeploymentSpecInternal, serviceLabels, reference)
            ?.let { mapper.convertValue(it) }
    }

    fun generateImageStream(
        deployId: String,
        auroraDeploymentSpecInternal: AuroraDeploymentSpecInternal,
        reference: OwnerReference
    ): JsonNode? {
        return auroraDeploymentSpecInternal.deploy?.let {

            val labels = openShiftObjectLabelService.createCommonLabels(
                auroraDeploymentSpecInternal, deployId,
                mapOf("releasedVersion" to it.version)
            )

            val imageStream = if (auroraDeploymentSpecInternal.type == TemplateType.development) {
                ImageStreamGenerator.createLocalImageStream(auroraDeploymentSpecInternal.name, labels, reference)
            } else {
                ImageStreamGenerator.createRemoteImageStream(
                    isName = auroraDeploymentSpecInternal.name,
                    isLabels = labels,
                    dockerRegistry = dockerRegistry,
                    dockerImagePath = it.dockerImagePath,
                    dockerTag = it.dockerTag
                    ,
                    reference = reference
                )
            }

            return mapper.convertValue(imageStream)
        }
    }

    fun generateTemplates(
        auroraDeploymentSpecInternal: AuroraDeploymentSpecInternal,
        mounts: List<Mount>?,
        ownerReference: OwnerReference
    ): List<JsonNode>? {

        val localTemplate = auroraDeploymentSpecInternal.localTemplate?.let {
            openShiftTemplateProcessor.generateObjects(
                template = it.templateJson as ObjectNode,
                parameters = it.parameters,
                auroraDeploymentSpecInternal = auroraDeploymentSpecInternal,
                version = it.version,
                replicas = it.replicas
            )
        }

        val template = auroraDeploymentSpecInternal.template?.let {
            val template = openShiftClient.get("template", "openshift", it.template)?.body as ObjectNode
            openShiftTemplateProcessor.generateObjects(
                template = template,
                parameters = it.parameters,
                auroraDeploymentSpecInternal = auroraDeploymentSpecInternal,
                version = it.version,
                replicas = it.replicas
            )
        }

        val objects: List<JsonNode> = listOf<JsonNode>().addIfNotNull(localTemplate).addIfNotNull(template)

        return objects.map {
            val result: JsonNode = if (it.openshiftKind == "deploymentconfig") {
                val dc: DeploymentConfig = jacksonObjectMapper().convertValue(it)
                val spec = dc.spec.template.spec
                spec.volumes.addAll(mounts.podVolumes(auroraDeploymentSpecInternal.name))
                spec.containers.forEach {
                    it.volumeMounts.addAll(mounts.volumeMount() ?: listOf())
                    it.env.addAll(createEnvVars(mounts, auroraDeploymentSpecInternal, routeSuffix))
                }
                jacksonObjectMapper().convertValue(dc)
            } else if (it.openshiftKind == "service" && it.openshiftName == auroraDeploymentSpecInternal.name) {

                val service: Service = jacksonObjectMapper().convertValue(it)

                if (service.metadata.annotations == null) {
                    service.metadata.annotations = HashMap<String, String>()
                }
                if (service.metadata.labels == null) {
                    service.metadata.labels = HashMap<String, String>()
                }

                service.metadata.labels.put("name", auroraDeploymentSpecInternal.name)

                auroraDeploymentSpecInternal.integration?.webseal?.let {
                    val host = it.host
                        ?: "${auroraDeploymentSpecInternal.name}-${auroraDeploymentSpecInternal.environment.namespace}"
                    service.metadata.annotations["sprocket.sits.no/service.webseal"] = host
                }

                auroraDeploymentSpecInternal.integration?.webseal?.roles?.let {
                    service.metadata.annotations["sprocket.sits.no/service.webseal-roles"] = it
                }

                jacksonObjectMapper().convertValue(service)
            } else it

            val metadataJson: JsonNode = jacksonObjectMapper().convertValue(listOf(ownerReference))
            val metadataNode: ObjectNode = result["metadata"] as ObjectNode
            metadataNode.set("ownerReferences", metadataJson)

            // TODO: AOS-2740 Denne linjen kan bort når vi fikset APIGroups endelig
            (result as ObjectNode).set("apiVersion", TextNode("v1"))
            result
        }
    }

    fun generateRoute(
        auroraDeploymentSpecInternal: AuroraDeploymentSpecInternal,
        routeLabels: Map<String, String>,
        ownerReference: OwnerReference
    ): List<JsonNode>? {
        return auroraDeploymentSpecInternal.route?.route?.map {
            val route = RouteGenerator.generateRoute(
                source = it,
                serviceName = auroraDeploymentSpecInternal.name,
                routeSuffix = routeSuffix,
                routeLabels = routeLabels,
                ownerReference = ownerReference
            )
            mapper.convertValue<JsonNode>(route)
        }
    }

    fun generateSecretsAndConfigMapsInTest(
        deployId: String,
        deploymentSpecInternal: AuroraDeploymentSpecInternal,
        provisioningResult: ProvisioningResult? = null,
        name: String
        ,
        ownerReference: OwnerReference
    ): List<JsonNode>? {
        return withLabelsAndMounts(deployId, deploymentSpecInternal, provisioningResult) { labels, mounts ->
            generateSecretsAndConfigMaps(
                appName = name,
                mounts = mounts ?: emptyList(),
                labels = labels,
                provisioningResult = provisioningResult,
                ownerReference = ownerReference
            )
        }
    }

    private fun generateSecretsAndConfigMaps(
        appName: String,
        mounts: List<Mount>,
        labels: Map<String, String>,
        provisioningResult: ProvisioningResult?
        ,
        ownerReference: OwnerReference
    ): List<JsonNode> {

        val schemaSecrets = provisioningResult?.schemaProvisionResults
            ?.let { DbhSecretGenerator.create(appName, it, labels, ownerReference) }
            ?: emptyList()

        val stsSecret = provisioningResult?.stsProvisioningResult
            ?.let { StsSecretGenerator.create(appName, it, labels, ownerReference) }

        val schemaSecretNames = schemaSecrets.map { it.metadata.name }

        return mounts
            .filter { !it.exist }
            .filter { !schemaSecretNames.contains(it.volumeName) }
            .mapNotNull { mount: Mount ->
                when (mount.type) {
                    ConfigMap -> mount.content
                        ?.let {
                            ConfigMapGenerator.create(
                                cmName = mount.getNamespacedVolumeName(appName),
                                cmLabels = labels,
                                cmData = it,
                                ownerReference = ownerReference
                            )
                        }
                    Secret -> mount.secretVaultName
                        ?.let { provisioningResult?.vaultResults?.getVaultData(it) }
                        ?.let {
                            SecretGenerator.create(
                                secretName = mount.getNamespacedVolumeName(appName),
                                secretLabels = labels,
                                secretData = it,
                                ownerReference = ownerReference
                            )
                        }
                    PVC -> null
                }
            }
            .plus(schemaSecrets)
            .addIfNotNull(stsSecret)
            .map { mapper.convertValue<JsonNode>(it) }
    }

    private fun generateBuilds(
        deploymentSpecInternal: AuroraDeploymentSpecInternal,
        deployId: String,
        ownerReference: OwnerReference
    ): List<JsonNode>? {
        return deploymentSpecInternal.build?.let {
            val labels = openShiftObjectLabelService.createCommonLabels(
                deploymentSpecInternal,
                deployId,
                name = deploymentSpecInternal.name
            )
            val build = BuildConfigGenerator.generate(it, deploymentSpecInternal.name, labels, ownerReference)

            listOf(mapper.convertValue(build))
        }
    }

    private fun <T> withLabelsAndMounts(
        deployId: String,
        deploymentSpecInternal: AuroraDeploymentSpecInternal,
        provisioningResult: ProvisioningResult? = null,
        c: (labels: Map<String, String>, mounts: List<Mount>?) -> T
    ): T {

        val mounts: List<Mount> = findAndCreateMounts(deploymentSpecInternal, provisioningResult)
        val labels = openShiftObjectLabelService.createCommonLabels(deploymentSpecInternal, deployId)
        return c(labels, mounts)
    }
}