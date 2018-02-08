package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftApiUrls
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.ClientType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType.*
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class OpenShiftCommandBuilder(
        @Value("\${openshift.url}") val baseUrl: String,
        @ClientType(API_USER) val userClient: OpenShiftResourceClient,
        val openShiftObjectGenerator: OpenShiftObjectGenerator
) {

    fun generateProjectRequest(environment: AuroraDeployEnvironment): OpenshiftCommand {

        val projectRequest = openShiftObjectGenerator.generateProjectRequest(environment)
        return createOpenShiftCommand(environment.namespace, projectRequest, false, false)
    }

    fun generateNamespace(environment: AuroraDeployEnvironment): OpenshiftCommand {
        val namespace = openShiftObjectGenerator.generateNamespace(environment)
        return createOpenShiftCommand(environment.namespace, namespace, true, true)
                .copy(operationType = UPDATE)
    }

    fun generateRolebindings(environment: AuroraDeployEnvironment): List<OpenshiftCommand> {
        val roleBindings = openShiftObjectGenerator.generateRolebindings(environment.permissions)
        return roleBindings.map {
            createOpenShiftCommand(environment.namespace, it, true, true)
                    .copy(operationType = UPDATE)
        }
    }

    fun generateApplicationObjects(
            deployId: String,
            deploymentSpec: AuroraDeploymentSpec,
            provisioningResult: ProvisioningResult?,
            mergeWithExistingResource: Boolean
    ): List<OpenshiftCommand> {

        val namespace = deploymentSpec.environment.namespace

        return openShiftObjectGenerator.generateApplicationObjects(deployId, deploymentSpec, provisioningResult)
                .map { createOpenShiftCommand(namespace, it, mergeWithExistingResource, false) }
                .flatMap { command ->
                    if (command.isType(UPDATE, "route") && mustRecreateRoute(command.payload, command.previous!!)) {
                        val deleteCommand = command.copy(operationType = DELETE)
                        val createCommand = command.copy(operationType = CREATE, payload = command.generated!!)
                        listOf(deleteCommand, createCommand)
                    } else {
                        listOf(command)
                    }
                }
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
    fun createOpenShiftCommand(namespace: String, newResource: JsonNode, mergeWithExistingResource: Boolean = true, retryGetResourceOnFailure: Boolean = false): OpenshiftCommand {

        val kind = newResource.openshiftKind
        val name = newResource.openshiftName

        val existingResource = if (mergeWithExistingResource)
            userClient.get(kind, namespace, name, retryGetResourceOnFailure)
        else null

        return if (existingResource == null) {
            OpenshiftCommand(CREATE, payload = newResource)
        } else {
            val mergedResource = no.skatteetaten.aurora.boober.service.openshift.mergeWithExistingResource(newResource, existingResource.body)
            OpenshiftCommand(UPDATE, mergedResource, existingResource.body, newResource)
        }
    }

    @JvmOverloads
    fun createOpenShiftDeleteCommands(name: String, namespace: String, deployId: String,
                                      apiResources: List<String> = listOf("BuildConfig", "DeploymentConfig", "ConfigMap", "Secret", "Service", "Route", "ImageStream")): List<OpenshiftCommand> {

        return apiResources.flatMap { kind ->
            val queryString = "labelSelector=app%3D$name%2CbooberDeployId%2CbooberDeployId%21%3D$deployId"
            val apiUrl = OpenShiftApiUrls.getCollectionPathForResource(baseUrl, kind, namespace)
            val url = "$apiUrl?$queryString"
            val body = userClient.get(url)?.body

            val items = body?.get("items")?.toList() ?: emptyList()
            items.filterIsInstance<ObjectNode>()
                    .onEach { it.put("kind", kind) }
        }.map { OpenshiftCommand(DELETE, payload = it, previous = it) }
    }

    private fun mustRecreateRoute(newRoute: JsonNode, previousRoute: JsonNode): Boolean {

        val hostPointer = "/spec/host"
        val pathPointer = "/spec/path"

        val hostChanged = previousRoute.at(hostPointer).textValue() != newRoute.at(hostPointer).textValue()
        val pathChanged = previousRoute.at(pathPointer) != newRoute.at(pathPointer)

        return hostChanged || pathChanged
    }
}