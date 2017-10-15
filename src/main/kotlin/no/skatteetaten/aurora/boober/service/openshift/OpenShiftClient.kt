package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
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
import org.springframework.http.HttpHeaders
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

        val performClient = if (kind == "namespace") {
            serviceAccountClient
        } else {
            userClient
        }

        return try {
            val res: JsonNode = when (command.operationType) {
                OperationType.CREATE -> performClient.post(kind, name, namespace, command.payload).body
                OperationType.UPDATE -> performClient.put(kind, name, namespace, command.payload).body
                OperationType.DELETE -> performClient.delete(kind, name, namespace).body
                OperationType.NOOP -> command.payload
            }
            OpenShiftResponse(command, res)
        } catch (e: OpenShiftException) {
            val response = if (e.cause is HttpClientErrorException) {
                val body = e.cause.responseBodyAsString
                try {
                    mapper.readValue<JsonNode>(body)
                } catch (je: Exception) {
                    mapper.convertValue<JsonNode>(mapOf("error" to body))
                }
            } else {
                null
            }
            OpenShiftResponse(command, response, success = false, exception = e.message)
        }
    }


    fun createOpenShiftCommand(namespace: String, json: JsonNode): OpenshiftCommand {

        val projectExist = projectExists(namespace)

        val generated = json.deepCopy<JsonNode>()

        val kind = json.openshiftKind
        val name = json.openshiftName

        //we do not update project objects
        if (kind == "projectrequest" && projectExist) {
            return OpenshiftCommand(OperationType.NOOP, payload = json)
        }

        val existingResource = if (projectExist) userClient.get(kind, name, namespace) else null
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
            json.updateField(existing, "/spec/template/spec/containers/0", "image")
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

        val currentUser = userClient.getExistingResource(headers, url)
        return currentUser?.body
    }


    fun isValidUser(user: String): Boolean {
        if (user.startsWith("system:serviceaccount")) {
            return true
        }
        return exist("$baseUrl/oapi/v1/users/$user")
    }

    fun isValidGroup(group: String): Boolean {

        return exist("$baseUrl/oapi/v1/groups/$group")
    }

    fun templateExist(template: String): Boolean {

        return exist("$baseUrl/oapi/v1/namespaces/openshift/templates/$template")
    }

    private fun exist(url: String): Boolean {
        val headers: HttpHeaders = serviceAccountClient.getAuthorizationHeaders()

        val existingResource = serviceAccountClient.getExistingResource(headers, url)
        return existingResource != null
    }

    fun isUserInGroup(user: String, group: String): Boolean {
        val headers: HttpHeaders = serviceAccountClient.getAuthorizationHeaders()

        val url = "$baseUrl/oapi/v1/groups/$group"

        val resource = serviceAccountClient.getExistingResource(headers, url)
        return resource?.body?.get("users")?.any { it.textValue() == user } ?: false
    }

    @JvmOverloads
    fun createOpenShiftDeleteCommands(name: String, namespace: String, deployId: String,
                                      apiResources: List<String> = listOf("BuildConfig", "DeploymentConfig", "ConfigMap", "Secret", "Service", "Route", "ImageStream")): List<OpenshiftCommand> {
        val headers: HttpHeaders = userClient.getAuthorizationHeaders()

        return apiResources.flatMap { kind ->
            val queryString = "labelSelector=app%3D$name%2CbooberDeployId%2CbooberDeployId%21%3D$deployId"
            val apiUrl = OpenShiftApiUrls.getCollectionPathForResource(baseUrl, kind, namespace)
            val url = "$apiUrl?$queryString"
            val body = userClient.getExistingResource(headers, url)?.body

            val items = body?.get("items")?.toList() ?: emptyList()
            items.filterIsInstance<ObjectNode>()
                    .onEach { it.put("kind", kind) }
        }.map {
            OpenshiftCommand(OperationType.DELETE, payload = it, previous = it)
        }
    }


    fun projectExists(name: String): Boolean {
        return exist("${baseUrl}/oapi/v1/projects/$name")
    }

    fun createUpdateRolebindingCommand(json: JsonNode, namespace: String): OpenshiftCommand {

        val kind = json.openshiftKind
        val name = json.openshiftName

        val generated = json.deepCopy<JsonNode>()
        val existing = userClient.get(kind, name, namespace)?.body ?: throw IllegalArgumentException("Admin rolebinding should exist")

        json.updateField(existing, "/metadata", "resourceVersion")

        return OpenshiftCommand(OperationType.UPDATE, json, existing, generated)
    }

    fun createUpdateNamespaceCommand(namespace: String, affiliation: String): OpenshiftCommand {
        val existing = serviceAccountClient.get("namespace", namespace, "")?.body ?: throw IllegalArgumentException("Namespace should exist")
        //do we really need to sleep here?
        val prev = (existing as ObjectNode).deepCopy()

        val labels = mapper.convertValue<JsonNode>(mapOf("affiliation" to affiliation))

        val metadata = existing.at("/metadata") as ObjectNode
        metadata.set("labels", labels)

        return OpenshiftCommand(OperationType.UPDATE, existing, previous = prev)
    }
}
