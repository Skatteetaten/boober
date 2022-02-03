package no.skatteetaten.aurora.boober.service

import org.springframework.stereotype.Service
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fkorotkov.openshift.from
import com.fkorotkov.openshift.importPolicy
import com.fkorotkov.openshift.metadata
import com.fkorotkov.openshift.newImageImportSpec
import com.fkorotkov.openshift.newImageStreamImport
import com.fkorotkov.openshift.spec
import com.fkorotkov.openshift.to
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import io.fabric8.openshift.api.model.ImageStreamImport
import io.fabric8.openshift.api.model.Route
import no.skatteetaten.aurora.boober.feature.TemplateType
import no.skatteetaten.aurora.boober.model.openshift.findErrorMessage
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType.CREATE
import no.skatteetaten.aurora.boober.service.openshift.OperationType.DELETE
import no.skatteetaten.aurora.boober.service.openshift.OperationType.UPDATE
import no.skatteetaten.aurora.boober.service.openshift.mergeWithExistingResource
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.convert
import no.skatteetaten.aurora.boober.utils.deploymentConfig
import no.skatteetaten.aurora.boober.utils.findDockerImageUrl
import no.skatteetaten.aurora.boober.utils.findErrorMessage
import no.skatteetaten.aurora.boober.utils.findImageChangeTriggerTagName
import no.skatteetaten.aurora.boober.utils.imageStream
import no.skatteetaten.aurora.boober.utils.namedUrl
import no.skatteetaten.aurora.boober.utils.namespacedNamedUrl
import no.skatteetaten.aurora.boober.utils.namespacedResourceUrl
import no.skatteetaten.aurora.boober.utils.nonGettableResources
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
import no.skatteetaten.aurora.boober.utils.preAppliedResources
import no.skatteetaten.aurora.boober.utils.resourceUrl

@Service
class OpenShiftCommandService(
    val openShiftClient: OpenShiftClient
) {

    fun orderObjects(
        objects: List<JsonNode>,
        templateType: TemplateType,
        namespace: String,
        mergeWithExistingResource: Boolean
    ): List<JsonNode> {
        // we cannot asume any order of the commands.
        val objectsWithoutISAndDc: List<JsonNode> =
            objects.filter { it.openshiftKind != "imagestream" && it.openshiftKind != "deploymentconfig" }

        val dcNode = objects.deploymentConfig()

        val dc = dcNode?.let {
            createOpenShiftCommand(namespace, it, mergeWithExistingResource)
        }

        val imageStreamNode = objects.imageStream()
        val imageStream = imageStreamNode?.let {
            createOpenShiftCommand(namespace, it, mergeWithExistingResource)
        }

        val imageStreamImport = when {
            dc == null || imageStream == null -> null
            templateType == TemplateType.development -> null
            imageStream.operationType == CREATE -> null
            else -> importImageStreamCommand(dc, imageStream)
        }

        // if deployment was paused we need to update is and import it first
        dc?.previous?.takeIf { deploymentPaused(it) }?.let {
            return listOfNotNull(imageStream?.payload)
                .addIfNotNull(imageStreamImport)
                .addIfNotNull(objectsWithoutISAndDc)
                .addIfNotNull(dcNode)
        }

        return objects.addIfNotNull(imageStreamImport)
    }

    private fun importImageStreamCommand(
        dcCommand: OpenshiftCommand?,
        isCommand: OpenshiftCommand?
    ): JsonNode? {

        if (dcCommand == null || isCommand == null) {
            return null
        }
        val dc = jacksonObjectMapper().convertValue<DeploymentConfig>(dcCommand.payload)
        val tagName = dc.findImageChangeTriggerTagName() ?: return null

        val imageStream = jacksonObjectMapper().convertValue<ImageStream>(isCommand.payload)
        val isName = imageStream.metadata.name
        val dockerUrl = imageStream.findDockerImageUrl(tagName) ?: return null
        val imageStreamImport = ImageStreamImportGenerator.create(dockerUrl, isName, dc.metadata.namespace)
        return jacksonObjectMapper().convertValue(imageStreamImport)
    }

    private fun deploymentPaused(command: JsonNode): Boolean {
        val dc = jacksonObjectMapper().convertValue<DeploymentConfig>(command)
        return dc.spec.replicas == 0
    }

    fun createOpenShiftCommand(
        namespace: String? = null,
        newResource: HasMetadata,
        mergeWithExistingResource: Boolean = true,
        retryGetResourceOnFailure: Boolean = false
    ): OpenshiftCommand {
        val resource: JsonNode = jacksonObjectMapper().convertValue(newResource)
        return createOpenShiftCommand(namespace, resource, mergeWithExistingResource, retryGetResourceOnFailure)
    }

    /**
     * @param mergeWithExistingResource Whether the OpenShift project the object belongs to exists. If it does, some object types
     * will be updated with information from the existing object to support the update.
     * @param retryGetResourceOnFailure Whether the GET request for the existing resource should be retried on errors
     * or not. You may want to retry the request if you are trying to update an object that has recently been created
     * by another task/process and you are not entirely sure it exists yet, for instance. The default is
     * <code>false</code>, because retrying everything will significantly impact performance of creating or updating
     * many objects.
     */
    fun createOpenShiftCommand(
        namespace: String? = null,
        newResource: JsonNode,
        mergeWithExistingResource: Boolean = true,
        retryGetResourceOnFailure: Boolean = false
    ): OpenshiftCommand {

        val (resourceUrl, namedUrl) = if (namespace == null) {
            newResource.resourceUrl to newResource.namedUrl
        } else {
            newResource.namespacedResourceUrl to newResource.namespacedNamedUrl
        }
        val kind = newResource.openshiftKind

        val shouldGetExistigResource =
            (mergeWithExistingResource && kind !in nonGettableResources) || kind in preAppliedResources

        val existingResource = if (shouldGetExistigResource)
            openShiftClient.get(kind, namedUrl, retryGetResourceOnFailure)
        else null

        return if (existingResource == null) {
            OpenshiftCommand(CREATE, payload = newResource, url = resourceUrl)
        } else {
            val existingBody = existingResource.body
            val mergedResource = existingBody?.let { mergeWithExistingResource(newResource, it) } ?: newResource
            OpenshiftCommand(
                operationType = UPDATE,
                payload = mergedResource,
                previous = existingBody,
                generated = newResource,
                url = namedUrl
            )
        }
    }

    // TODO: Should pvc be deletable?
    val deletableResources = listOf(
        "BuildConfig",
        "DeploymentConfig",
        "ConfigMap",
        "Secret",
        "Service",
        "Route",
        "AuroraCname",
        "AuroraAzureCname",
        "AuroraAzureApp",
        "AuroraApim",
        "ImageStream",
        "BigIp",
        "CronJob",
        "Job",
        "Deployment",
        "Alert"
    )

    fun createOpenShiftDeleteCommands(
        name: String,
        namespace: String,
        deployId: String,
        apiResources: List<String> = deletableResources
    ): List<OpenshiftCommand> {

        val labelSelectors = listOf("app=$name", "booberDeployId", "booberDeployId!=$deployId")
        return apiResources
            .filter { kind -> kind.lowercase() != "auroracname" || openShiftClient.k8sVersionOfAtLeast("1.16") }
            .filter { kind -> kind.lowercase() != "auroraazurecname" || openShiftClient.k8sVersionOfAtLeast("1.16") }
            .filter { kind -> kind.lowercase() != "alert" || openShiftClient.k8sVersionOfAtLeast("1.16") }
            .flatMap { kind -> openShiftClient.getByLabelSelectors(kind, namespace, labelSelectors) }
            .map {
                try {
                    val url = OpenShiftResourceClient.generateUrl(
                        kind = it.openshiftKind,
                        name = it.openshiftName,
                        namespace = namespace
                    )
                    OpenshiftCommand(DELETE, payload = it, previous = it, url = url)
                } catch (e: Throwable) {
                    throw e
                }
            }
    }

    private fun mustRecreateRoute(newRoute: JsonNode, previousRoute: JsonNode?): Boolean {
        if (previousRoute == null) {
            return false
        }

        val hostPointer = "/spec/host"
        val pathPointer = "/spec/path"

        val hostChanged = previousRoute.at(hostPointer).textValue() != newRoute.at(hostPointer).textValue()
        val pathChanged = previousRoute.at(pathPointer) != newRoute.at(pathPointer)

        return hostChanged || pathChanged
    }

    // TODO: This could be retried
    fun createAndApplyObjects(
        namespace: String,
        it: JsonNode,
        mergeWithExistingResource: Boolean
    ): List<OpenShiftResponse> {
        val command = createOpenShiftCommand(namespace, it, mergeWithExistingResource)

        val commands: List<OpenshiftCommand> =

            if (command.isType(UPDATE, "route") && mustRecreateRoute(command.payload, command.previous)) {
                val deleteCommand = command.copy(operationType = DELETE)
                val createCommand = command.copy(
                    operationType = CREATE,
                    url = command.payload.namespacedResourceUrl,
                    payload = command.generated!!
                )
                listOf(deleteCommand, createCommand)
            } else if (command.isType(UPDATE, "route") && command.rolesEqualAndProcessingDone()) {
                listOf(command.setWebsealDone())
            } else {
                listOf(command)
            }

        val results = commands.map { openShiftClient.performOpenShiftCommand(it) }

        return results.map { response ->
            findErrorMessage(response)
                ?.let { response.copy(success = false, exception = it) }
                ?: response
        }
    }

    // TODO: How we handle errors here is pretty complicated, Clean this up when we move to WebClient?
    fun findErrorMessage(response: OpenShiftResponse): String? {
        if (!response.success) {
            return response.exception
        }

        val body = response.responseBody ?: return null

        // right now only imagestreamimport is checked for errors in status. Route is maybe something we can add here?
        return when {
            body.openshiftKind == "route" -> body.convert<Route>().findErrorMessage()
            body.openshiftKind == "imagestreamimport" -> body.convert<ImageStreamImport>().findErrorMessage()
            else -> null
        }
    }
}

object ImageStreamImportGenerator {

    fun create(dockerImageUrl: String, imageStreamName: String, isiNamespace: String): ImageStreamImport {
        return newImageStreamImport {
            metadata {
                name = imageStreamName
                namespace = isiNamespace
            }
            spec {
                import = true
                images = listOf(
                    newImageImportSpec {
                        from {
                            kind = "DockerImage"
                            name = dockerImageUrl
                        }

                        to {
                            name = "default"
                        }

                        importPolicy {
                            scheduled = true
                        }
                    }
                )
            }
        }
    }
}
