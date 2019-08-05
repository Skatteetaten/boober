package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient.Companion.generateUrl
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.ClientType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException

enum class OperationType { GET, CREATE, UPDATE, DELETE, NOOP }

data class OpenshiftCommand @JvmOverloads constructor(
    val operationType: OperationType,
    val url: String,
    val payload: JsonNode = NullNode.getInstance(),
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
    val exception: String? = null,
    val httpErrorCode: Int? = null
) {

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
            val httpCode = if (e.cause is HttpClientErrorException) {
                e.cause.statusCode.value()
            } else {
                null
            }
            return OpenShiftResponse(
                command,
                response,
                success = false,
                exception = e.message,
                httpErrorCode = httpCode
            )
        }
    }
}

fun List<OpenShiftResponse>.describe() = this.map {
    val exceptionMessage = it.exception?.let {
        "failed=$it"
    }
    "${it.command.operationType} ${it.command.payload.openshiftKind}/${it.command.payload.openshiftName} $exceptionMessage"
}

fun List<OpenShiftResponse>.describeString() = this.describe().joinToString(", ")

fun List<OpenShiftResponse>.resource(kind: String): OpenShiftResponse? =
    this.find { it.responseBody?.openshiftKind == kind }

fun List<OpenShiftResponse>.deploymentConfig(): OpenShiftResponse? = this.resource("deploymentconfig")
fun List<OpenShiftResponse>.imageStream(): OpenShiftResponse? = this.resource("imagestream")
fun List<OpenShiftResponse>.imageStreamImport(): OpenShiftResponse? = this.resource("imagestreamimport")

data class OpenShiftGroups(val groupUsers: Map<String, List<String>>) {

    fun getGroupsForUser(user: String): List<String> = groupUsers.filter { it.value.contains(user) }.keys.toList()

    fun getUsersForGroup(group: String) = groupUsers[group] ?: emptyList()

    fun groupExist(group: String) = groupUsers.containsKey(group)
}

@Service
class OpenShiftClient(
    @ClientType(API_USER) val userClient: OpenShiftResourceClient,
    @ClientType(SERVICE_ACCOUNT) val serviceAccountClient: OpenShiftResourceClient,
    val mapper: ObjectMapper
) {

    val logger: Logger = LoggerFactory.getLogger(OpenShiftClient::class.java)

    fun performOpenShiftCommand(namespace: String, command: OpenshiftCommand): OpenShiftResponse {

        val kind = command.payload.openshiftKind

        val performClient = getClientForKind(kind)

        return try {
            val res: JsonNode? = when (command.operationType) {
                OperationType.GET -> performClient.get(command.url, retry = false)?.body
                OperationType.CREATE -> performClient.post(command.url, command.payload).body
                OperationType.UPDATE -> performClient.put(command.url, command.payload).body
                OperationType.DELETE -> performClient.delete(command.url)?.body
                OperationType.NOOP -> command.payload
            }
            OpenShiftResponse(command, res)
        } catch (e: OpenShiftException) {
            OpenShiftResponse.fromOpenShiftException(e, command)
        }
    }

    fun findCurrentUser(token: String): JsonNode? {

        val url = generateUrl(kind = "user", name = "~")
        val headers: HttpHeaders = userClient.createHeaders(token)

        val currentUser = userClient.get(url, headers)
        return currentUser?.body
    }

    @Cacheable("templates")
    fun getTemplate(template: String): JsonNode? {
        return try {

            val url = generateUrl(kind = "template", namespace = "openshift", name = template)
            serviceAccountClient.get(url)?.body
        } catch (e: Exception) {
            logger.debug("Failed getting template={}", template)
            null
        }
    }

    @Cacheable("groups")
    fun getGroups(): OpenShiftGroups {

        fun getAllDeclaredUserGroups(): Map<String, List<String>> {
            val url = generateUrl(kind = "group")
            val groupItems = getResponseBodyItems(url)
            return groupItems
                .filter { it["users"] is ArrayNode }
                .associate { users ->
                    val name = users["metadata"]["name"].asText()
                    name to (users["users"] as ArrayNode).map { it.asText() }
                }
        }

        fun getAllImplicitUserGroups(): Map<String, List<String>> {
            val implicitGroup = "system:authenticated"
            val userItems = getResponseBodyItems(generateUrl("user"))
            return mapOf(implicitGroup to userItems.map { it["metadata"]["name"].asText() })
        }

        return OpenShiftGroups(getAllDeclaredUserGroups() + getAllImplicitUserGroups())
    }

    fun resourceExists(kind: String, namespace: String, name: String): Boolean {
        val response = getClientForKind(kind).get(kind, namespace, name, false)
        return response?.statusCode?.is2xxSuccessful ?: false
    }

    fun projectExists(name: String): Boolean {
        val url = generateUrl("project", name = name)
        serviceAccountClient.get(url, retry = false)?.body?.let {
            val phase = it.at("/status/phase").textValue()
            if (phase == "Active") {
                return true
            } else {
                throw IllegalStateException("Project $name already exists but is in an illegal state ($phase)")
            }
        }
        return false
    }

    fun get(kind: String, url: String, retry: Boolean = true): ResponseEntity<JsonNode>? {
        return getClientForKind(kind).get(url, retry = retry)
    }

    /**
     * @param labelSelectors examples: name=someapp, name!=someapp (name label not like), name (name label must be set)
     */
    fun getByLabelSelectors(kind: String, namespace: String, labelSelectors: List<String>): List<JsonNode> {
        val queryString = labelSelectors.joinToString(",")

        val apiUrl = generateUrl(kind, namespace)
        val url = "$apiUrl?labelSelector=$queryString"
        val body = getClientForKind(kind).get(url)?.body

        val items = body?.get("items")?.toList() ?: emptyList()
        return items.filterIsInstance<ObjectNode>()
            .onEach {
                it.put("kind", kind)
            }
    }

    /**
     * Some operations that users need to perform require privileges that they typically do not have. Therefore, for
     * some OpenShift kinds, we use a client that is based on the token of the service account of the current
     * container instead of that of the user. This way we can in practice escalate the privileges of the user performing
     * the request.
     */
    private fun getClientForKind(kind: String): OpenShiftResourceClient {
        return if (listOf("namespace", "route").contains(kind.toLowerCase())) {
            serviceAccountClient
        } else {
            userClient
        }
    }

    private fun getResponseBodyItems(url: String): ArrayNode {
        val response: ResponseEntity<JsonNode> = serviceAccountClient.get(url)!!
        return response.body!!["items"] as ArrayNode
    }
}
