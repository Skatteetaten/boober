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
import org.apache.http.client.utils.URLEncodedUtils
import org.apache.http.message.BasicNameValuePair
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
) {
    fun isType(operationType: OperationType, kind: String): Boolean {

        if (payload.openshiftKind != kind) return false
        if (operationType != operationType) return false
        return true
    }
}

data class OpenShiftResponse @JvmOverloads constructor(
        val command: OpenshiftCommand,
        val responseBody: JsonNode? = null,
        val success: Boolean = true,
        val exception: String? = null) {

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

data class UserGroup(val user: String, val group: String)

data class OpenShiftGroups(private val groupUserPairs: List<UserGroup>) {

    private val groupUsers: Map<String, List<String>> by lazy {
        groupUserPairs.groupBy({ it.group }, { it.user })
    }


    private val userGroups: Map<String, List<String>> by lazy {
        groupUserPairs.groupBy({ it.user }, { it.group })
    }

    fun getGroupsForUser(user: String) = userGroups[user] ?: emptyList()

    fun groupExist(group: String) = groupUsers.containsKey(group)
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

    fun findCurrentUser(token: String): JsonNode? {

        val url = "$baseUrl/oapi/v1/users/~"
        val headers: HttpHeaders = userClient.createHeaders(token)

        val currentUser = userClient.get(url, headers)
        return currentUser?.body
    }

    @Cacheable("templates")
    fun getTemplate(template: String): JsonNode? {
        return try {
            serviceAccountClient.get("$baseUrl/oapi/v1/namespaces/openshift/templates/$template")?.body
        } catch (e: Exception) {
            logger.debug("Failed getting template={}", template)
            null
        }
    }

    @Cacheable("groups")
    fun getGroups(): OpenShiftGroups {

        fun getAllDeclaredUserGroups(): List<UserGroup> {
            val groupItems = getResponseBodyItems("${baseUrl}/oapi/v1/groups/")
            return groupItems.flatMap {
                val name = it["metadata"]["name"].asText()
                (it["users"] as ArrayNode).map { UserGroup(it.asText(), name) }

            }
        }

        fun getAllImplicitUserGroups(): List<UserGroup> {
            val implicitGroup = "system:authenticated"
            val userItems = getResponseBodyItems("${baseUrl}/oapi/v1/users")
            return userItems.map { UserGroup(it["metadata"]["name"].asText(), implicitGroup) }
        }

        return OpenShiftGroups(getAllDeclaredUserGroups() + getAllImplicitUserGroups())
    }

    fun projectExists(name: String): Boolean {
        serviceAccountClient.get("${baseUrl}/oapi/v1/projects/$name", retry = false)?.body?.let {
            val phase = it.at("/status/phase").textValue()
            if (phase == "Active") {
                return true
            } else {
                throw IllegalStateException("Project ${name} already exists but is in an illegal state ($phase)")
            }
        }
        return false
    }

    fun get(kind: String, namespace: String, name: String, retry: Boolean = true): ResponseEntity<JsonNode>? {
        return getClientForKind(kind).get(kind, namespace, name, retry)
    }

    /**
     * @param labelSelectors examples: name=someapp, name!=someapp (name label not like), name (name label must be set)
     */
    fun getByLabelSelectors(kind: String, namespace: String, labelSelectors: List<String>): List<JsonNode> {
        val queryString = urlEncode(Pair("labelSelector", labelSelectors.joinToString(",")))
        val apiUrl = OpenShiftApiUrls.getCollectionPathForResource(baseUrl, kind, namespace)
        val url = "$apiUrl?$queryString"
        val body = getClientForKind(kind).get(url)?.body

        val items = body?.get("items")?.toList() ?: emptyList()
        return items.filterIsInstance<ObjectNode>()
                .onEach { it.put("kind", kind) }
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

    private fun getResponseBodyItems(url: String): ArrayNode {
        val response: ResponseEntity<JsonNode> = serviceAccountClient.get(url)!!
        return response.body["items"] as ArrayNode
    }

    companion object {

        @JvmStatic
        fun urlEncode(vararg queryParams: Pair<String, String>) =
                URLEncodedUtils.format(queryParams.map { BasicNameValuePair(it.first, it.second) }, Charsets.UTF_8)
    }
}
