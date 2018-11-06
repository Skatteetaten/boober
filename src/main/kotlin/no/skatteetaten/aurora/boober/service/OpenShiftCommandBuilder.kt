package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType.CREATE
import no.skatteetaten.aurora.boober.service.openshift.OperationType.DELETE
import no.skatteetaten.aurora.boober.service.openshift.OperationType.UPDATE
import no.skatteetaten.aurora.boober.service.openshift.mergeWithExistingResource
import no.skatteetaten.aurora.boober.service.openshift.resource
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.springframework.stereotype.Service

@Service
class OpenShiftCommandBuilder(
    val openShiftClient: OpenShiftClient,
    val openShiftObjectGenerator: OpenShiftObjectGenerator
) {

    fun generateProjectRequest(environment: AuroraDeployEnvironment): OpenshiftCommand {

        val projectRequest = openShiftObjectGenerator.generateProjectRequest(environment)
        return createOpenShiftCommand(environment.namespace, projectRequest, false, false)
    }

    fun generateNamespace(environment: AuroraDeployEnvironment): OpenshiftCommand {
        val namespace = openShiftObjectGenerator.generateNamespace(environment)
        return createMergedUpdateCommand(environment.namespace, namespace)
    }

    fun generateRolebindings(environment: AuroraDeployEnvironment): List<OpenshiftCommand> {
        return openShiftObjectGenerator.generateRolebindings(environment.permissions)
            .map { createOpenShiftCommand(environment.namespace, it, true, true) }
    }

    private fun createMergedUpdateCommand(namespace: String, it: JsonNode) =
        createOpenShiftCommand(namespace, it, true, true).copy(operationType = UPDATE)

    fun generateOpenshiftCommands(
        deployId: String,
        deploymentSpecInternal: AuroraDeploymentSpecInternal,
        provisioningResult: ProvisioningResult?,
        mergeWithExistingResource: Boolean,
        ownerReference: OwnerReference
    ): List<OpenshiftCommand> {

        val namespace = deploymentSpecInternal.environment.namespace

        val commands = openShiftObjectGenerator.generateApplicationObjects(
            deployId,
            deploymentSpecInternal,
            provisioningResult,
            ownerReference
        ).map { createOpenShiftCommand(namespace, it, mergeWithExistingResource, false) }
            .flatMap { command ->
                if (command.isType(UPDATE, "route") && mustRecreateRoute(command.payload, command.previous)) {
                    val deleteCommand = command.copy(operationType = DELETE)
                    val createCommand = command.copy(operationType = CREATE, payload = command.generated!!)
                    listOf(deleteCommand, createCommand)
                } else {
                    listOf(command)
                }
            }

        // if deploy was paused we need to do imageStream first
        if (!deploymentPaused(commands)) {
            return commands
        }

        // we cannot asume any order of the commands.
        val commandsWithoutDCAndIS = commands.filter {
            val kind = it.payload.openshiftKind
            kind != "deploymentconfig" && kind != "imagestream"
        }

        val dc = commands.resource("deploymentconfig")
        val imageStream = commands.resource("imagestream")?.let { listOf(it) } ?: emptyList()

        return imageStream.addIfNotNull(dc) + commandsWithoutDCAndIS
    }

    private fun deploymentPaused(commands: List<OpenshiftCommand>): Boolean {
        commands.resource("deploymentconfig")?.let {
            val dc = jacksonObjectMapper().convertValue<DeploymentConfig>(it.previous!!)
            return dc.spec.replicas == 0
        }
        return false
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
        namespace: String,
        newResource: JsonNode,
        mergeWithExistingResource: Boolean = true,
        retryGetResourceOnFailure: Boolean = false
    ): OpenshiftCommand {

        val kind = newResource.openshiftKind
        val name = newResource.openshiftName

        val existingResource = if (mergeWithExistingResource)
            openShiftClient.get(kind, namespace, name, retryGetResourceOnFailure)
        else null

        return if (existingResource == null) {
            OpenshiftCommand(CREATE, payload = newResource)
        } else {
            val mergedResource = mergeWithExistingResource(newResource, existingResource.body)
            OpenshiftCommand(UPDATE, mergedResource, existingResource.body, newResource)
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