package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import no.skatteetaten.aurora.boober.model.AuroraPermissions
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

enum class OperationType { CREATE, UPDATE, DELETE, NOOP }

data class OpenshiftCommand @JvmOverloads constructor(
        val operationType: OperationType,
        val payload: JsonNode,
        val previous: JsonNode? = null,
        val generated: JsonNode? = null
)

data class OpenShiftResponse(
        val command: OpenshiftCommand,
        val responseBody: JsonNode
) {
    fun labelChanged(name: String): Boolean {
        val pointer = "/metadata/labels/$name"
        val response = responseBody.at(pointer).asText()
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

    fun performOpenShiftCommand(cmd: OpenshiftCommand, namespace: String): OpenShiftResponse {

        val kind = cmd.payload.openshiftKind

        val performClient = if (listOf("project", "rolebinding").contains(kind)) {
            serviceAccountClient
        } else {
            userClient
        }
        val name = cmd.payload.openshiftName

        val res = when (cmd.operationType) {
            OperationType.CREATE -> performClient.post(kind, name, namespace, cmd.payload).body
            OperationType.UPDATE -> performClient.put(kind, name, namespace, cmd.payload).body
            OperationType.DELETE -> performClient.delete(kind, name, namespace, cmd.payload).body
            OperationType.NOOP -> cmd.payload
        }

        return OpenShiftResponse(cmd, res)

    }


    fun prepare(namespace: String, json: JsonNode): OpenshiftCommand? {


        val generated = json.deepCopy<JsonNode>()
        val kind = json.openshiftKind

        val name = json.openshiftName

        val existingResource = userClient.get(kind, name, namespace)

        if (existingResource == null) {
            return OpenshiftCommand(OperationType.CREATE, payload = json)
        }

        //we do not update project objects
        if (kind == "project") {
            return OpenshiftCommand(OperationType.NOOP, payload = json)
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

    fun hasUserAccess(user: String, permissions: AuroraPermissions?): Boolean {
        if (permissions == null) {
            return true
        }

        val groupSize = permissions.groups?.size ?: 0
        val userSize = permissions.users?.size ?: 0
        if (groupSize + userSize == 0) {
            return true
        }

        val validUser: Boolean = permissions.users?.any { user == it && isValidUser(user) } ?: false

        val validGroup = permissions.groups?.any { isUserInGroup(user, it) } ?: false

        return validUser || validGroup

    }

    fun isValidUser(user: String): Boolean {
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

    private fun isUserInGroup(user: String, group: String): Boolean {
        val headers: HttpHeaders = serviceAccountClient.getAuthorizationHeaders()

        val url = "$baseUrl/oapi/v1/groups/$group"

        val resource = serviceAccountClient.getExistingResource(headers, url)
        return resource?.body?.get("users")?.any { it.textValue() == user } ?: false
    }

    fun createOpenshiftDeleteCommands(name: String, namespace: String, deployId: String,
                                      kinds: List<String> = listOf("deploymentconfigs", "configmaps", "secrets", "services", "routes", "imagestreams")): List<OpenshiftCommand> {
        val headers: HttpHeaders = userClient.getAuthorizationHeaders()


        return kinds.flatMap {
            val apiType = if (it in listOf("services", "configmaps", "secrets")) "api" else "oapi"
            val url = "$baseUrl/$apiType/v1/namespaces/$namespace/$it?labelSelector=app%3D$name%2CbooberDeployId%2CbooberDeployId%21%3D$deployId"
            userClient.getExistingResource(headers, url)?.body?.get("items")?.toList() ?: emptyList()
        }.map {
            OpenshiftCommand(OperationType.DELETE, payload = it, previous = it)
        }
    }


    fun projectExist(name: String): Boolean {
        val headers: HttpHeaders = userClient.getAuthorizationHeaders()
        val url = "$baseUrl/oapi/v1/projects"
        val projects = userClient.getExistingResource(headers, url)?.body?.get("items")?.toList() ?: emptyList()


        val canSee = projects.any { it.at("/metadata/name").asText() == name }

        if(!canSee) {
            // Potential bug if the user can view, but not change the project.
            if (exist("$url/$name")) {
                throw IllegalAccessException("Project exist but you do not have permission to modify it")
            }
        }

        return canSee
    }

}
