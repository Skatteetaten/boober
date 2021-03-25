package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.utils.RetryingRestTemplateWrapper

private val logger = KotlinLogging.logger {}

data class MattermostSendMessageRequest(
    val message: String,
    @JsonProperty("channel_id")
    val channelId: String,
    val props: MattermostProps
)

data class MattermostProps(
    val attachments: List<Attachment>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Attachment(
    val color: String? = null,
    val text: String? = null,
    val title: String? = null,
    @JsonProperty("title_link")
    val titleLink: String? = null,
    val fields: List<AttachmentField>? = null
)

data class AttachmentField(
    val short: Boolean,
    val title: String,
    val value: String
)

enum class AttachmentColor(val hex: String) {
    Red("#FF0000"),
    Green("#008000");

    override fun toString(): String = hex
}

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
    fun sendMessage(channelId: String, message: String, attachments: List<Attachment> = emptyList()): Exception? {
        val response = runCatching {
            restTemplateWrapper.post(
                url = "/posts",
                body = MattermostSendMessageRequest(
                    message = message,
                    channelId = channelId,
                    props = MattermostProps(
                        attachments
                    )
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
