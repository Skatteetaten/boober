package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.ClientType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
import no.skatteetaten.aurora.boober.utils.updateField
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.annotation.Cacheable
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException

enum class OperationType { CREATE, UPDATE, DELETE, NOOP }

data class OpenshiftCommand @JvmOverloads constructor(
        val operationType: OperationType,
        val payload: JsonNode,
        val previous: JsonNode? = null,
        val generated: JsonNode? = null
)

data class OpenShiftResponse @JvmOverloads constructor(
        val command: OpenshiftCommand,
        val responseBody: JsonNode? = null,
        val success: Boolean = true,
        val exception: String? = null) {
    fun labelChanged(name: String): Boolean {
        val pointer = "/metadata/labels/$name"
        val response = responseBody?.at(pointer)?.asText()
        val previous = command.previous?.at(pointer)?.asText() ?: ""
        return response != previous
    }

    companion object {
        fun fromOpenShiftException(e: OpenShiftException, command: OpenshiftCommand): OpenShiftResponse {
            val response = if (e.cause is HttpClientErrorException) {
                val body = e.cause.responseBodyAsString
                try {
                    jacksonObjectMapper().readValue<JsonNode>(body)
                } catch (je: Exception) {
                    jacksonObjectMapper().convertValue<JsonNode>(mapOf("error" to body))
                }
            } else {
                null
            }
            return OpenShiftResponse(command, response, success = false, exception = e.message)
        }

    }
}

@Service
class OpenShiftClient(
        @Value("\${openshift.url}") val baseUrl: String,
        @ClientType(API_USER) val userClient: OpenShiftResourceClient,
        @ClientType(SERVICE_ACCOUNT) val serviceAccountClient: OpenShiftResourceClient,
        val mapper: ObjectMapper
) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftClient::class.java)

    fun performOpenShiftCommand(namespace: String, command: OpenshiftCommand): OpenShiftResponse {

        val kind = command.payload.openshiftKind
        val name = command.payload.openshiftName

        val performClient = getClientForKind(kind)

        return try {
            val res: JsonNode = when (command.operationType) {
                OperationType.CREATE -> performClient.post(kind, namespace, name, command.payload).body
                OperationType.UPDATE -> performClient.put(kind, namespace, name, command.payload).body
                OperationType.DELETE -> performClient.delete(kind, namespace, name).body
                OperationType.NOOP -> command.payload
            }
            OpenShiftResponse(command, res)
        } catch (e: OpenShiftException) {
            OpenShiftResponse.fromOpenShiftException(e, command)
        }
    }

    /**
     * @param projectExist Whether the OpenShift project the object belongs to exists. If it does, some object types
     * will be updated with information from the existing object to support the update.
     * @param retryGetResourceOnFailure Whether the GET request for the existing resource should be retried on errors
     * or not. You may want to retry the request if you are trying to update an object that has recently been created
     * by another task/process and you are not entirely sure it exists yet, for instance. The default is
     * <code>false</code>, because retrying everything will significantly impact performance of creating or updating
     * many objects.
     */
    fun createOpenShiftCommand(namespace: String, json: JsonNode, projectExist: Boolean, retryGetResourceOnFailure: Boolean = false): OpenshiftCommand {

        val generated = json.deepCopy<JsonNode>()

        val kind = json.openshiftKind
        val name = json.openshiftName

        //we do not update project objects
        if (kind == "projectrequest" && projectExist) {
            return OpenshiftCommand(OperationType.NOOP, payload = json)
        }

        val existingResource = if (projectExist) userClient.get(kind, namespace, name, retryGetResourceOnFailure) else null
        if (existingResource == null) {
            return OpenshiftCommand(OperationType.CREATE, payload = json)
        }
        // ProjectRequest will always create an admin rolebinding, so if we get a command to create one, we just
        // swap it out with an update command.
        val isCreateAdminRoleBindingCommand = kind == "rolebinding" && name == "admin"
        if (isCreateAdminRoleBindingCommand) {
            return createUpdateRolebindingCommand(json, namespace)
        }

        val existing = existingResource.body

        json.updateField(existing, "/metadata", "resourceVersion")

        if (kind == "service") {
            json.updateField(existing, "/spec", "clusterIP")
        }

        if (kind == "persistentvolumeclaim") {
            json.updateField(existing, "/spec", "volumeName")
        }

        if (kind == "deploymentconfig") {
            json.updateField(existing, "/spec/triggers/0/imageChangeParams", "lastTriggeredImage")
            val containerCount = (json.at("/spec/template/spec/containers") as ArrayNode).size()
            (0..containerCount).forEach {
                json.updateField(existing, "/spec/template/spec/containers/$it", "image")
            }
        }

        if (kind == "buildconfig") {
            val triggerCount = (json.at("/spec/triggers") as ArrayNode).size()
            (0..triggerCount).forEach {
                json.updateField(existing, "/spec/triggers/$it/imageChange", "lastTriggeredImageID")
            }
        }

        return OpenshiftCommand(OperationType.UPDATE, json, existing, generated)
    }

    fun findCurrentUser(token: String): JsonNode? {

        val url = "$baseUrl/oapi/v1/users/~"
        val headers: HttpHeaders = userClient.createHeaders(token)

        val currentUser = userClient.get(url, headers)
        return currentUser?.body
    }


    @Cacheable("groups")
    fun isValidGroup(group: String): Boolean {
        return getGroups(group) != null
    }

    fun getTemplate(template: String): JsonNode? {
        return try {
            userClient.get("$baseUrl/oapi/v1/namespaces/openshift/templates/$template")?.body
        } catch (e: Exception) {
            logger.debug("Failed getting template={}", template)
            null
        }
    }

    fun getGroups(group: String): ResponseEntity<JsonNode>? {

        val url = "$baseUrl/oapi/v1/groups/$group"
        return serviceAccountClient.get(url)
    }

    @Cacheable("groups")
    fun isUserInGroup(user: String, group: String): Boolean {
        val resource = getGroups(group)
        return resource?.body?.get("users")?.any { it.textValue() == user } ?: false
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
        }.map {
            OpenshiftCommand(OperationType.DELETE, payload = it, previous = it)
        }
    }


    fun projectExists(name: String): Boolean {
        serviceAccountClient.get("${baseUrl}/oapi/v1/projects/$name", retry = false)?.body?.let {
            val phase = it.at("/status/phase").textValue()
            if (phase == "Active") {
                return true
            } else {
                throw IllegalStateException("Project ${name} is not Active")
            }
        }
        return false
    }

    fun createUpdateRolebindingCommand(json: JsonNode, namespace: String): OpenshiftCommand {

        val kind = json.openshiftKind
        val name = json.openshiftName

        val generated = json.deepCopy<JsonNode>()
        val existing = userClient.get(kind, namespace, name)?.body ?: throw IllegalArgumentException("Admin rolebinding should exist")

        json.updateField(existing, "/metadata", "resourceVersion")

        return OpenshiftCommand(OperationType.UPDATE, json, existing, generated)
    }

    fun createUpdateNamespaceCommand(namespace: String, affiliation: String): OpenshiftCommand {
        val existing = serviceAccountClient.get("namespace", "", namespace)?.body ?: throw IllegalArgumentException("Namespace should exist")
        //do we really need to sleep here?
        val prev = (existing as ObjectNode).deepCopy()

        val labels = mapper.convertValue<JsonNode>(mapOf("affiliation" to affiliation))

        val metadata = existing.at("/metadata") as ObjectNode
        metadata.set("labels", labels)

        return OpenshiftCommand(OperationType.UPDATE, existing, previous = prev)
    }

    /**
     * Some operations that users need to perform require privileges that they typically do not have. Therefore, for
     * some OpenShift kinds, we use a client that is based on the token of the service account of the current
     * container instead of that of the user. This way we can in practice escalate the privileges of the user performing
     * the request.
     */
    private fun getClientForKind(kind: String): OpenShiftResourceClient {
        return if (listOf("namespace", "route").contains(kind)) {
            serviceAccountClient
        } else {
            userClient
        }
    }
}
