package no.skatteetaten.aurora.boober.service.openshift

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.ClientType
import no.skatteetaten.aurora.boober.TokenSource.API_USER
import no.skatteetaten.aurora.boober.TokenSource.SERVICE_ACCOUNT
import no.skatteetaten.aurora.boober.feature.WEBSEAL_DONE_ANNOTATION
import no.skatteetaten.aurora.boober.feature.WEBSEAL_ROLES_ANNOTATION
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient.Companion.generateUrl
import no.skatteetaten.aurora.boober.utils.annotation
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.springframework.cache.annotation.Cacheable
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException

private val logger = KotlinLogging.logger {}

enum class OperationType { GET, CREATE, UPDATE, DELETE, NOOP }

data class OpenshiftCommand(
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

    fun rolesEqualAndProcessingDone(): Boolean {
        if (previous == null) {
            return false
        }

        previous.annotation(WEBSEAL_DONE_ANNOTATION) ?: return false

        return payload.annotation(WEBSEAL_ROLES_ANNOTATION) == previous.annotation(WEBSEAL_ROLES_ANNOTATION)
    }

    fun setWebsealDone(): OpenshiftCommand {
        val annotations = payload.get("metadata").get("annotations") as ObjectNode
        annotations.replace(WEBSEAL_DONE_ANNOTATION, TextNode(previous?.annotation(WEBSEAL_DONE_ANNOTATION)))
        return this.copy(payload = payload)
    }
}

data class OpenShiftResponse(
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
                exception = e.message, // TODO If we create a better exception here we can remove some code in the resourceClient
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

    fun performOpenShiftCommand(command: OpenshiftCommand): OpenShiftResponse {

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
    fun getTemplate(template: String): JsonNode {

        val url = generateUrl(kind = "template", namespace = "openshift", name = template)
        return serviceAccountClient.get(url)?.body
            ?: throw IllegalArgumentException("Could not find template for url=$url")
    }

    @Cacheable("groups")
    fun getGroups(): OpenShiftGroups {

        val url = generateUrl(kind = "group")
        val groupItems = getResponseBodyItems(url)
        val groups = groupItems
            .filter { it["users"] is ArrayNode }
            .associate { users ->
                val name = users["metadata"]["name"].asText()
                name to (users["users"] as ArrayNode).map { it.asText() }
            }
        return OpenShiftGroups(groups)
    }

    @Cacheable("version")
    fun version(): String {
        serviceAccountClient.get("/version", retry = false)?.body?.let {
            val version = it.at("/gitVersion").textValue()
            return version.substring(1, version.indexOf("+"))
        }
        throw java.lang.IllegalStateException("Unable to determine kubernetes version")
    }

    /**
     * @return true if given semver version is found to be higher or equal to kubernetes semver version
     */
    fun k8sVersionOfAtLeast(ensure: String): Boolean {
        val k8s = version().split(".")
        val v = ensure.split(".")
        for (index in 0 until Math.min(k8s.size, v.size)) {
            if (k8s[index].toInt() < v[index].toInt()) return false
        }
        return true
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
        return if (listOf("namespace", "route", "auroracname").contains(kind.toLowerCase())) {
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
