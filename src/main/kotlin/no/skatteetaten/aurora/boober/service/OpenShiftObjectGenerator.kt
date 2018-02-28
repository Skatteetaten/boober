package no.skatteetaten.aurora.boober.service

import SecretGenerator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import no.skatteetaten.aurora.boober.Boober
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.ConfigMapGenerator
import no.skatteetaten.aurora.boober.service.internal.ContainerGenerator
import no.skatteetaten.aurora.boober.service.internal.DbhSecretGenerator
import no.skatteetaten.aurora.boober.service.internal.DeploymentConfigGenerator
import no.skatteetaten.aurora.boober.service.internal.ImageStreamGenerator
import no.skatteetaten.aurora.boober.service.internal.findAndCreateMounts
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import no.skatteetaten.aurora.boober.utils.filterNullValues
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class OpenShiftObjectGenerator(
        @Value("\${boober.docker.registry}") val dockerRegistry: String,
        val openShiftObjectLabelService: OpenShiftObjectLabelService,
        val velocityTemplateJsonService: VelocityTemplateJsonService,
        val mapper: ObjectMapper,
        val openShiftTemplateProcessor: OpenShiftTemplateProcessor,
        val openShiftClient: OpenShiftResourceClient) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftObjectGenerator::class.java)


    fun generateDeploymentRequest(name: String): JsonNode {
        logger.trace("Generating deploy request for name $name")
        return mergeVelocityTemplate("deploymentrequest.json", mapOf("name" to name))

    }

    fun generateApplicationObjects(deployId: String, auroraDeploymentSpec: AuroraDeploymentSpec,
                                   provisioningResult: ProvisioningResult? = null): List<JsonNode> {

        return withLabelsAndMounts(deployId, auroraDeploymentSpec, provisioningResult, { labels, mounts ->

            listOf<JsonNode>()
                    .addIfNotNull(generateDeploymentConfig(auroraDeploymentSpec, labels, mounts))
                    .addIfNotNull(generateService(auroraDeploymentSpec, labels))
                    .addIfNotNull(generateImageStream(deployId, auroraDeploymentSpec))
                    .addIfNotNull(generateBuilds(auroraDeploymentSpec, deployId))
                    .addIfNotNull(generateMounts(mounts, labels, provisioningResult, auroraDeploymentSpec.name))
                    .addIfNotNull(generateRoute(auroraDeploymentSpec, labels))
                    .addIfNotNull(generateTemplate(auroraDeploymentSpec))
                    .addIfNotNull(generateLocalTemplate(auroraDeploymentSpec))
        })
    }


    fun generateProjectRequest(environment: AuroraDeployEnvironment): JsonNode {

        return mergeVelocityTemplate("projectrequest.json", mapOf(
                "namespace" to environment.namespace
        ))
    }

    fun generateNamespace(environment: AuroraDeployEnvironment): JsonNode {

        return mergeVelocityTemplate("namespace.json", mapOf(
                "namespace" to environment.namespace,
                "affiliation" to environment.affiliation
        ))
    }

    fun generateRolebindings(permissions: Permissions): List<JsonNode> {
        val admin = mergeVelocityTemplate("rolebinding.json", mapOf(
                "permission" to permissions.admin,
                "name" to "admin"
        ))

        val view = permissions.view?.let {
            mergeVelocityTemplate("rolebinding.json", mapOf(
                    "permission" to it,
                    "name" to "view"))
        }

        return listOf(admin).addIfNotNull(view)
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
                ?: throw IllegalArgumentException("ApplicationPlattformHanndler ${auroraDeploymentSpec.applicationPlatform} is not present")
        val deployment = applicationPlatformHandler.handleAuroraDeployment(auroraDeploymentSpec, labels, mounts)


        val container = deployment.containers.map { ContainerGenerator.create(it) }

        val dc = DeploymentConfigGenerator.create(deployment, container)

        return mapper.convertValue(dc)
    }

    fun generateService(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>): JsonNode? {
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
                        "prometheus.io/port" to it.port
                )
            } ?: mapOf("prometheus.io/scrape" to "false")


            mergeVelocityTemplate("service.json", mapOf(
                    "labels" to labels,
                    "name" to auroraDeploymentSpec.name,
                    "annotations" to prometheusAnnotations.addIfNotNull(webseal).addIfNotNull(websealRoles)
            ))
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

    fun generateRoute(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>): List<JsonNode>? {
        return auroraDeploymentSpec.route?.route?.map {
            logger.trace("Route is {}", it)
            val host = auroraDeploymentSpec.assembleRouteHost(it.host ?: auroraDeploymentSpec.name)
            val routeParams = mapOf(
                    "name" to auroraDeploymentSpec.name,
                    "route" to it,
                    "host" to host,
                    "labels" to labels)
            mergeVelocityTemplate("route.json", routeParams)
        }
    }

    fun generateMountsInTest(deployId: String, deploymentSpec: AuroraDeploymentSpec, provisioningResult: ProvisioningResult? = null, name: String): List<JsonNode>? {

        return withLabelsAndMounts(deployId, deploymentSpec, provisioningResult, { labels, mounts -> generateMounts(mounts, labels, provisioningResult, name) })
    }

    private fun generateMounts(mounts: List<Mount>?, labels: Map<String, String>, provisioningResult: ProvisioningResult?, appName: String): List<JsonNode>? {


        val schemaSecrets = provisioningResult?.schemaProvisionResults?.let { DbhSecretGenerator.create(appName, it, labels) }

        val schemaSecretNames = schemaSecrets?.map { it.metadata.name } ?: emptyList()

        val objects = mounts?.filter { !it.exist }
                ?.filter { !schemaSecretNames.contains(it.volumeName) }
                ?.mapNotNull {

                    when (it.type) {

                        MountType.ConfigMap -> {

                            val content = it.content?.let {
                                it.filterNullValues().mapValues { it.value }
                            } ?: mapOf()

                            ConfigMapGenerator.create(it.volumeName.ensureStartWith(appName, "-"), labels, content)
                                    .let { mapper.convertValue<JsonNode>(it) }
                        }
                        MountType.Secret -> {

                            val content = it.secretVaultName?.let {
                                provisioningResult?.vaultResults?.getVaultData(it)
                            }
                            SecretGenerator.create(it.volumeName.ensureStartWith(appName, "-"), labels, content)
                                    .let { mapper.convertValue<JsonNode>(it) }

                        }
                        MountType.PVC -> null
                    }
                } ?: emptyList()

        return objects.addIfNotNull(schemaSecrets?.map { mapper.convertValue<JsonNode>(it) })
    }

    private fun generateBuilds(deploymentSpec: AuroraDeploymentSpec, deployId: String): List<JsonNode>? {
        return deploymentSpec.build?.let {
            val buildName = if (it.buildSuffix != null) {
                "${deploymentSpec.name}-${it.buildSuffix}"
            } else {
                deploymentSpec.name
            }

            val labels = openShiftObjectLabelService.createCommonLabels(deploymentSpec, deployId, name = buildName)
            val buildParams = mapOf(
                    "labels" to labels,
                    "buildName" to buildName,
                    "build" to it,
                    "dockerRegistry" to dockerRegistry
            )
            val applicationPlatform = it.applicationPlatform
            val template = when (applicationPlatform) {
                "java" -> "build-config.json"
                else -> "build-config-web.json"
            }
            val bc = mergeVelocityTemplate(template, buildParams)

            val testBc = if (it.testGitUrl != null) {
                mergeVelocityTemplate("jenkins-build-config.json", buildParams)
            } else {
                null
            }

            listOf(bc).addIfNotNull(testBc)
        }
    }

    private fun <T> withLabelsAndMounts(deployId: String, deploymentSpec: AuroraDeploymentSpec,
                                        provisioningResult: ProvisioningResult? = null,
                                        c: (labels: Map<String, String>, mounts: List<Mount>?) -> T): T {

        val mounts: List<Mount> = findAndCreateMounts(deploymentSpec, provisioningResult)
        val labels = openShiftObjectLabelService.createCommonLabels(deploymentSpec, deployId)
        return c(labels, mounts)
    }


    fun mergeVelocityTemplate(template: String, content: Map<String, Any?>): JsonNode {
        return velocityTemplateJsonService.renderToJson(template, content)
    }
}