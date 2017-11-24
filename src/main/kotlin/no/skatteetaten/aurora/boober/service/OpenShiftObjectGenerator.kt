package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationPlatform.java
import no.skatteetaten.aurora.boober.model.ApplicationPlatform.web
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.service.internal.DeploymentConfigGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.utils.addIf
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.ensureStartWith
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class OpenShiftObjectGenerator(
        val userDetailsProvider: UserDetailsProvider,
        val velocityTemplateJsonService: VelocityTemplateJsonService,
        val mapper: ObjectMapper,
        val openShiftTemplateProcessor: OpenShiftTemplateProcessor,
        val openShiftClient: OpenShiftResourceClient) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftObjectGenerator::class.java)

    companion object {
        @JvmStatic
        val MAX_LABEL_VALUE_LENGTH = 63

        /**
         * Returns a new Map where each value has been truncated as to not exceed the
         * <code>MAX_LABEL_VALUE_LENGTH</code> max length.
         * Truncation is done by cutting of characters from the start of the value, leaving only the last
         * MAX_LABEL_VALUE_LENGTH characters.
         */
        fun toOpenShiftLabelNameSafeMap(labels: Map<String, String>): Map<String, String> {
            return labels.mapValues {
                val startIndex = (it.value.length - MAX_LABEL_VALUE_LENGTH).takeIf { it >= 0 } ?: 0
                it.value.substring(startIndex)
            }
        }
    }

    fun generateBuildRequest(name: String): JsonNode {
        logger.trace("Generating build request for name $name")
        return mergeVelocityTemplate("buildrequest.json", mapOf("name" to name))

    }

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
                    .addIfNotNull(generateImageStream(auroraDeploymentSpec, labels))
                    .addIfNotNull(generateBuilds(auroraDeploymentSpec, deployId))
                    .addIfNotNull(generateMount(mounts, labels))
                    .addIfNotNull(generateRoute(auroraDeploymentSpec, labels))
                    .addIfNotNull(generateTemplate(auroraDeploymentSpec))
                    .addIfNotNull(generateLocalTemplate(auroraDeploymentSpec))
                    .addIf(provisioningResult?.schemaProvisionResults != null,
                            generateSecretsForSchemas(auroraDeploymentSpec, provisioningResult?.schemaProvisionResults!!))
        })
    }

    fun generateSecretsForSchemas(deploymentSpec: AuroraDeploymentSpec, schemaProvisionResults: SchemaProvisionResults): List<JsonNode> {

        return schemaProvisionResults.results.map {
            mergeVelocityTemplate("secret.json", mapOf(
                    "deploymentSpec" to deploymentSpec,
                    "dbhSchema" to it.dbhSchema
            ))
        }
    }

    fun generateProjectRequest(auroraDeploymentSpec: AuroraDeploymentSpec): JsonNode {

        return mergeVelocityTemplate("projectrequest.json", mapOf(
                "namespace" to auroraDeploymentSpec.namespace
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
                                 mounts: List<Mount>?): JsonNode? =
            DeploymentConfigGenerator(mapper, velocityTemplateJsonService).create(auroraDeploymentSpec, labels, mounts)

    fun generateService(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>): JsonNode? {
        return auroraDeploymentSpec.deploy?.let {

            val webseal = it.webseal?.let {
                val host = it.host ?: "${auroraDeploymentSpec.name}-${auroraDeploymentSpec.namespace}"
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

    fun generateImageStream(deployId: String, auroraDeploymentSpec: AuroraDeploymentSpec) =
            withLabelsAndMounts(deployId, auroraDeploymentSpec) { labels, _ ->
                generateImageStream(auroraDeploymentSpec, labels)
            }

    fun generateImageStream(auroraDeploymentSpec: AuroraDeploymentSpec, labels: Map<String, String>): JsonNode? {
        return auroraDeploymentSpec.deploy?.let {
            mergeVelocityTemplate("imagestream.json", mapOf(
                    "labels" to toOpenShiftLabelNameSafeMap(labels + mapOf("releasedVersion" to it.version)),
                    "deploy" to it,
                    "name" to auroraDeploymentSpec.name,
                    "type" to auroraDeploymentSpec.type.name
            ))
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
            val host = it.host?.let {
                auroraDeploymentSpec.assembleRouteHost(it)
            }
            val routeParams = mapOf(
                    "name" to auroraDeploymentSpec.name,
                    "route" to it,
                    "host" to host,
                    "labels" to labels)
            mergeVelocityTemplate("route.json", routeParams)
        }
    }

    fun generateMount(deploymentSpec: AuroraDeploymentSpec, deployId: String): List<JsonNode>? {

        return withLabelsAndMounts(deployId, deploymentSpec, null, { labels, mounts -> generateMount(mounts, labels) })
    }

    private fun generateMount(mounts: List<Mount>?, labels: Map<String, String>): List<JsonNode>? {
        return mounts?.filter { !it.exist }?.map {
            logger.trace("Create manual mount {}", it)
            val mountParams = mapOf(
                    "mount" to it,
                    "labels" to labels
            )
            mergeVelocityTemplate("mount.json", mountParams)
        }
    }

    private fun generateBuilds(auroraDeploymentSpec: AuroraDeploymentSpec, deployId: String): List<JsonNode>? {
        return auroraDeploymentSpec.build?.let {
            val buildName = if (it.buildSuffix != null) {
                "${auroraDeploymentSpec.name}-${it.buildSuffix}"
            } else {
                auroraDeploymentSpec.name
            }

            val buildParams = mapOf(
                    "labels" to createLabelsFromDeploymentSpec(auroraDeploymentSpec, deployId, buildName),
                    "buildName" to buildName,
                    "build" to it
            )
            val applicationPlatform = it.applicationPlatform
            val template = when (applicationPlatform) {
                java -> "build-config.json"
                web -> "build-config-web.json"
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

    fun createLabelsFromDeploymentSpec(auroraDeploymentSpec: AuroraDeploymentSpec, deployId: String, name: String = auroraDeploymentSpec.name): Map<String, String> {
        val labels = mapOf(
                "app" to name,
                "updatedBy" to userDetailsProvider.getAuthenticatedUser().username.replace(":", "-"),
                "affiliation" to auroraDeploymentSpec.affiliation,
                "booberDeployId" to deployId
        )
        return labels
    }

    fun createMountsFromDeploymentSpec(auroraDeploymentSpec: AuroraDeploymentSpec): List<Mount> {
        val mounts: List<Mount> = auroraDeploymentSpec.volume?.mounts?.map {
            if (it.exist) {
                it
            } else {
                it.copy(volumeName = it.volumeName.ensureStartWith(auroraDeploymentSpec.name, "-"))
            }
        } ?: emptyList()


        val configMount = auroraDeploymentSpec.volume?.config?.let {

            Mount(path = "/u01/config/configmap",
                    type = MountType.ConfigMap,
                    volumeName = auroraDeploymentSpec.name,
                    mountName = "config",
                    exist = false,
                    content = it, permissions = null)
        }

        val secretMount = auroraDeploymentSpec.volume?.secrets?.let {
            Mount(path = "/u01/config/secret",
                    type = MountType.Secret,
                    volumeName = auroraDeploymentSpec.name,
                    mountName = "secrets",
                    exist = false,
                    content = it)
        }

        val certMount = auroraDeploymentSpec.deploy?.certificateCn?.let {
            Mount(path = "/u01/secrets/app/${auroraDeploymentSpec.name}-cert",
                    type = MountType.Secret,
                    volumeName = "${auroraDeploymentSpec.name}-cert",
                    mountName = "${auroraDeploymentSpec.name}-cert",
                    exist = true,
                    content = null)
            //TODO: Add sprocket content here
        }
        return mounts.addIfNotNull(configMount).addIfNotNull(secretMount).addIfNotNull(certMount)
    }

    fun createMountsFromProvisioningResult(provisioningResult: ProvisioningResult): List<Mount> {

/*
        val certMount = auroraDeploymentSpec.deploy?.certificateCn?.let {
            Mount(path = "/u01/secrets/app/${auroraDeploymentSpec.name}-cert",
                    type = MountType.Secret,
                    volumeName = "${auroraDeploymentSpec.name}-cert",
                    mountName = "${auroraDeploymentSpec.name}-cert",
                    exist = true,
                    content = null)
            //TODO: Add sprocket content here
        }
*/
        val schemaResults: List<SchemaProvisionResult> = (provisioningResult.schemaProvisionResults?.results ?: emptyList())
        val databaseMounts = schemaResults.map {
            val mountPath = "${it.request.schemaName}-db".toLowerCase()
            Mount(path = "/u01/secrets/app/$mountPath",
                    type = MountType.Secret,
                    mountName = mountPath,
                    volumeName = mountPath,
                    exist = true,
                    content = null)
        }

        return databaseMounts
    }

    private fun <T> withLabelsAndMounts(deployId: String, deploymentSpec: AuroraDeploymentSpec,
                                        provisioningResult: ProvisioningResult? = null,
                                        c: (labels: Map<String, String>, mounts: List<Mount>?) -> T): T {

        val deploymentSpecMounts = createMountsFromDeploymentSpec(deploymentSpec)
        val provisioningMounts = provisioningResult?.let { createMountsFromProvisioningResult(it) }.orEmpty()
        val allMounts: List<Mount> = deploymentSpecMounts + provisioningMounts

        val labels = toOpenShiftLabelNameSafeMap(createLabelsFromDeploymentSpec(deploymentSpec, deployId))
        return c(labels, allMounts)
    }

    fun generateImageStreamImport(name: String, dockerImage: String): JsonNode {

        return mergeVelocityTemplate("imagestreamimport.json", mapOf("name" to name, "docker" to dockerImage))
    }

    fun mergeVelocityTemplate(template: String, content: Map<String, Any?>): JsonNode {
        return velocityTemplateJsonService.renderToJson(template, content)
    }
}