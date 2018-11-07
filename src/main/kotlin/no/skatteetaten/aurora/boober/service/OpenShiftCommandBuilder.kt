package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.ImageStreamImportGenerator
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType.CREATE
import no.skatteetaten.aurora.boober.service.openshift.OperationType.DELETE
import no.skatteetaten.aurora.boober.service.openshift.OperationType.UPDATE
import no.skatteetaten.aurora.boober.service.openshift.mergeWithExistingResource
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.deploymentConfig
import no.skatteetaten.aurora.boober.utils.findDockerImageUrl
import no.skatteetaten.aurora.boober.utils.findImageChangeTriggerTagName
import no.skatteetaten.aurora.boober.utils.imageStream
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.springframework.stereotype.Service

@Service
class OpenShiftCommandBuilder(
    val openShiftClient: OpenShiftClient,
    val openShiftObjectGenerator: OpenShiftObjectGenerator
) {

    fun generateProjectRequest(environment: AuroraDeployEnvironment): List<OpenshiftCommand> {

        val projectRequest = openShiftObjectGenerator.generateProjectRequest(environment)
        return createOpenShiftCommands(environment.namespace, projectRequest, false, false)
    }

    fun generateNamespace(environment: AuroraDeployEnvironment): List<OpenshiftCommand> {
        val namespace = openShiftObjectGenerator.generateNamespace(environment)
        return createMergedUpdateCommand(environment.namespace, namespace)
    }

    fun generateRolebindings(environment: AuroraDeployEnvironment): List<OpenshiftCommand> {
        return openShiftObjectGenerator.generateRolebindings(environment.permissions)
            .flatMap { createOpenShiftCommands(environment.namespace, it, true, true) }
    }

    private fun createMergedUpdateCommand(namespace: String, it: JsonNode) =
        createOpenShiftCommands(namespace, it, true, true).map { it.copy(operationType = UPDATE) }

    fun generateOpenshiftObjects(
        deployId: String,
        deploymentSpecInternal: AuroraDeploymentSpecInternal,
        provisioningResult: ProvisioningResult?,
        mergeWithExistingResource: Boolean,
        ownerReference: OwnerReference
    ): List<JsonNode> {

        val namespace = deploymentSpecInternal.environment.namespace

        val unorderedObjects = openShiftObjectGenerator.generateApplicationObjects(
            deployId,
            deploymentSpecInternal,
            provisioningResult,
            ownerReference
        )

        return orderObjects(unorderedObjects, deploymentSpecInternal.type, namespace, mergeWithExistingResource)
    }

    fun orderObjects(
        objects: List<JsonNode>,
        templateType: TemplateType,
        namespace: String,
        mergeWithExistingResource: Boolean
    ): List<JsonNode> {
        // we cannot asume any order of the commands.
        val objectsWithoutIS: List<JsonNode> = objects.filter { it.openshiftKind != "imagestream" }

        val dc = objects.deploymentConfig()?.let {
            createOpenShiftCommands(namespace, it, mergeWithExistingResource).first()
        }

        val imageStreamNode = objects.imageStream()
        val imageStream = imageStreamNode?.let {
            createOpenShiftCommands(namespace, it, mergeWithExistingResource).first()
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
                .addIfNotNull(objectsWithoutIS)
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
        val imageStreamImport = ImageStreamImportGenerator.create(dockerUrl, isName)
        return imageStreamImport.toJsonNode()
    }

    private fun deploymentPaused(command: JsonNode): Boolean {
        val dc = jacksonObjectMapper().convertValue<DeploymentConfig>(command)
        return dc.spec.replicas == 0
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
    fun createOpenShiftCommands(
        namespace: String,
        newResource: JsonNode,
        mergeWithExistingResource: Boolean = true,
        retryGetResourceOnFailure: Boolean = false
    ): List<OpenshiftCommand> {

        val kind = newResource.openshiftKind
        val name = newResource.openshiftName

        val existingResource = if (mergeWithExistingResource && kind != "imagestreamimport")
            openShiftClient.get(kind, namespace, name, retryGetResourceOnFailure)
        else null

        val command = if (existingResource == null) {
            OpenshiftCommand(CREATE, payload = newResource)
        } else {
            val mergedResource = mergeWithExistingResource(newResource, existingResource.body)
            OpenshiftCommand(
                operationType = UPDATE,
                payload = mergedResource,
                previous = existingResource.body,
                generated = newResource
            )
        }
        return if (command.isType(UPDATE, "route") && mustRecreateRoute(command.payload, command.previous)) {
            val deleteCommand = command.copy(operationType = DELETE)
            val createCommand = command.copy(operationType = CREATE, payload = command.generated!!)
            listOf(deleteCommand, createCommand)
        } else {
            listOf(command)
        }
    }

    @JvmOverloads
    fun createOpenShiftDeleteCommands(
        name: String,
        namespace: String,
        deployId: String,
        apiResources: List<String> = listOf(
            "BuildConfig",
            "DeploymentConfig",
            "ConfigMap",
            "Secret",
            "Service",
            "Route",
            "ImageStream"
        )
    ): List<OpenshiftCommand> {

        // TODO: This cannot be change until we remove the app label
        val labelSelectors = listOf("app=$name", "booberDeployId", "booberDeployId!=$deployId")
        return apiResources
            .flatMap { kind -> openShiftClient.getByLabelSelectors(kind, namespace, labelSelectors) }
            .map { OpenshiftCommand(DELETE, payload = it, previous = it) }
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
}