package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.ServiceTypes.CANTUS
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
import no.skatteetaten.aurora.boober.utils.jsonMapper

private val logger = KotlinLogging.logger {}

data class TagResult(val cmd: TagCommand, val response: JsonNode?, val success: Boolean)

data class TagCommand(
    val name: String,
    val from: String,
    val to: String,
    val fromRegistry: String,
    val toRegistry: String = fromRegistry
)

data class CantusTagCommand(
    val from: String,
    val to: String
)

data class CantusManifestCommand(
    val tagUrls: List<String>
)

data class CantusFailure(
    val url: String,
    val errorMessage: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuroraResponse<T : Any>(
    val items: List<T> = emptyList(),
    val failure: List<CantusFailure> = emptyList(),
    val success: Boolean = true,
    val message: String = "OK",
    val failureCount: Int = failure.size,
    val successCount: Int = items.size,
    val count: Int = failureCount + successCount
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImageTagResource(
    val auroraVersion: String? = null,
    val appVersion: String? = null,
    val dockerVersion: String,
    val dockerDigest: String,
    val requestUrl: String
)

@Component
class CantusRestTemplateWrapper(@TargetService(CANTUS) restTemplate: RestTemplate, retries: Int = 3) :
    RetryingRestTemplateWrapper(restTemplate, retries)

@Service
class CantusService(
    val client: CantusRestTemplateWrapper,
    @Value("\${integrations.docker.registry}") val dockerRegistry: String
) {

    fun getImageInformation(repo: String, name: String, tag: String): List<ImageTagResource> {
        val cantusManifestCommand = CantusManifestCommand(
            listOf(
                "$dockerRegistry/$repo/$name/$tag"
            )
        )

        val response = runCatching {
            client.post(
                body = cantusManifestCommand,
                type = AuroraResponse::class,
                url = "/manifest"
            )
        }.also(::logResult)
            .getOrNull()
            ?.body

        if (response?.success != true) throw CantusServiceException("Unable to receive manifest. cause=${response.messageOrDefault}")

        return response.items.map {
            jsonMapper().convertValue<ImageTagResource>(it)
        }
    }

    fun tag(cmd: TagCommand): TagResult {
        val cantusCmd = CantusTagCommand(
            "${cmd.fromRegistry}/${cmd.name}:${cmd.from}",
            "${cmd.toRegistry}/${cmd.name}:${cmd.to}"
        )
        val response = runCatching {
            client.post(body = cantusCmd, type = JsonNode::class, url = "/tag")
        }.also(::logResult)
            .getOrNull()

        val success = response != null

        return TagResult(cmd, response?.body, success)
    }
}

// TODO: should we log as error instead?
private fun logResult(result: Result<ResponseEntity<*>>) {
    result.onFailure { logger.warn(it) { "Received error from Cantus. Caused by ${it.cause}" } }
        .onSuccess { logger.info("Response from Cantus code=${it.statusCode} body=${it.body}") }
}

private val AuroraResponse<*>?.messageOrDefault: String
    get() = this?.message ?: "empty body"
