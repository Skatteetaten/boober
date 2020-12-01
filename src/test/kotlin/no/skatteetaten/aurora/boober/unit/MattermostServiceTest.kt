package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.messageContains
import io.mockk.clearAllMocks
import no.skatteetaten.aurora.boober.service.MattermostRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.MattermostService
import no.skatteetaten.aurora.boober.service.NotificationServiceException
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.url
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpStatus

class MattermostServiceTest {

    private val server = MockWebServer()
    val service = MattermostService(
        restTemplateWrapper = MattermostRestTemplateWrapper(
            restTemplate = RestTemplateBuilder().build(),
            url = server.url
        ),
        mattermostToken = "mattermostoken"
    )

    @BeforeEach
    fun setup() {
        clearAllMocks()
    }

    @Test
    fun `verify send message to channel`() {
        val response = jsonMapper().createObjectNode()

        server.execute(HttpStatus.CREATED.value() to response) {
            assertThat(service.sendMessage("channelid", """mattermost message"""))
                .isNull()
        }
    }
    @Test
    fun `verify exception when send message to channel fails`() {
        val response = jsonMapper().createObjectNode()

        server.execute(HttpStatus.INTERNAL_SERVER_ERROR.value() to response) {
            assertThat(service.sendMessage("channelid", """mattermost message"""))
                .isNotNull()
                .isInstanceOf(NotificationServiceException::class)
                .messageContains("Was not able to send notification")
        }
    }
}
