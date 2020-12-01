package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

private val logger = KotlinLogging.logger {}

data class MattermostSendMessageRequest(
    val message: String,
    @JsonProperty("channel_id")
    val channelId: String
)

@Component
class MattermostRestTemplateWrapper(
    @TargetService(ServiceTypes.GENERAL) restTemplate: RestTemplate,
    @Value("\${integrations.mattermost.url}") val url: String
) : RetryingRestTemplateWrapper(
    restTemplate = restTemplate,
    retries = 0,
    baseUrl = "$url/api/v4"
)

@Service
class MattermostService(
    val restTemplateWrapper: MattermostRestTemplateWrapper,
    @Value("\${integrations.mattermost.token}") val mattermostToken: String
) {
    fun sendMessage(channelId: String, message: String): Exception? {
        val response = runCatching {
            restTemplateWrapper.post(
                url = "/posts",
                body = MattermostSendMessageRequest(
                    message = message,
                    channelId = channelId
                ),
                type = JsonNode::class,
                headers = HttpHeaders().apply {
                    setBearerAuth(mattermostToken)
                }
            )
        }.onFailure {
            logger.error { it }
        }.getOrNull()

        if (response == null || response.statusCode != HttpStatus.CREATED) {
            return NotificationServiceException("Was not able to send notification to mattermost channel_id=$channelId")
        }

        return null
    }
}
