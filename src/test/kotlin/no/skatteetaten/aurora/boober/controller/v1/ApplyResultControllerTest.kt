package no.skatteetaten.aurora.boober.controller.v1

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.ResultActions
import org.springframework.web.client.HttpClientErrorException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import com.ninjasquad.springmockk.MockkBean
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.clearAllMocks
import io.mockk.every
import no.skatteetaten.aurora.boober.controller.internal.Response
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.BitbucketService
import no.skatteetaten.aurora.boober.service.DeployHistoryEntry
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.objectMapperWithTime
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.status
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk

@AutoConfigureMockMvc
@SpringBootTest
class ApplyResultControllerTest : AbstractControllerTest() {

    @MockkBean
    private lateinit var bitbucketService: BitbucketService

    @MockkBean
    private lateinit var userDetailsProvider: UserDetailsProvider

    val deployHistory = loadJsonResource("deployhistory.json").let {
        objectMapperWithTime.convertValue<List<JsonNode>>(it)
    }.map { it.toString() }

    @BeforeEach
    fun setup() {
        every { userDetailsProvider.getAuthenticatedUser() } returns User(
            "hero", "token", "Jayne Cobb", grantedAuthorities = listOf(
                SimpleGrantedAuthority("APP_PaaS_utv"), SimpleGrantedAuthority("APP_PaaS_drift")
            )
        )
    }

    @AfterEach
    fun clearAllmocks() {
        clearAllMocks()
    }

    @Test
    fun `Get deploy history`() {

        every {
            bitbucketService.getFiles(any(), any(), any())
        } returns listOf("firstHistoryItem")

        every {
            bitbucketService.getFile(any(), any(), any(), any())
        } returns deployHistory.first()

        mockMvc.get(Path("/v1/apply-result/{auroraConfigName}", auroraConfigRef.name)) {
            statusIsOk()
                .assertDeployHistoryEqual(deployHistory.first())
        }
    }

    @Test
    fun `Get deploy history by id`() {

        val deployId = "123"

        every {
            bitbucketService.getFile(any(), any(), any(), any())
        } returns deployHistory.first()

        mockMvc.get(Path("/v1/apply-result/{auroraConfigName}/{deployId}", auroraConfigRef.name, deployId)) {
            statusIsOk()
                .assertDeployHistoryEqual(deployHistory.first())
        }
    }

    @Test
    fun `Get deploy history by id return not found when no DeployResult`() {
        val deployId = "invalid-id"
        every {
            bitbucketService.getFile(any(), any(), any(), any())
        } returns null

        mockMvc.get(
            Path("/v1/apply-result/{auroraConfigName}/{deployId}", auroraConfigRef.name, deployId),
            docsIdentifier = "get-v1-apply-result-auroraConfigName-deployId-failure"
        ) {
            status(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `Get error response when findDeployResultById throws HttpClientErrorException`() {

        val deployId = "1235"
        every {
            bitbucketService.getFile(any(), any(), any(), any())
        } throws HttpClientErrorException(HttpStatus.NOT_FOUND)

        mockMvc.get(
            Path("/v1/apply-result/{auroraConfigName}/{deployId}", auroraConfigRef.name, deployId),
            docsIdentifier = "get-v1-apply-result-auroraConfigName-deployId-failure-not-found-http"
        ) {
            status(HttpStatus.INTERNAL_SERVER_ERROR)
                .responseJsonPath("$.message").contains(deployId)
                .responseJsonPath("$.message").contains(auroraConfigRef.name)
        }
    }

    @Test
    fun `Should filter out secret information if stored`() {

        val deployHistoryWithSecret = loadJsonResource("deployhistoryWithSecret.json").let {
            objectMapperWithTime.convertValue<List<JsonNode>>(it)
        }.map { it.toString() }

        every {
            bitbucketService.getFiles(any(), any(), any())
        } returns listOf("firstHistoryItem")

        every {
            bitbucketService.getFile(any(), any(), any(), any())
        } returns deployHistoryWithSecret.first()

        mockMvc.get(Path("/v1/apply-result/{auroraConfigName}", auroraConfigRef.name)) {
            statusIsOk()
                .assertDeployHistoryEqual(deployHistory.first())
        }
    }

    private fun ResultActions.assertDeployHistoryEqual(expected: String) {
        val actualHistoryAsAny =
            objectMapperWithTime.readValue<Response>(this.andReturn().response.contentAsString).items.first()
        val actualHistory = objectMapperWithTime.convertValue<DeployHistoryEntry>(actualHistoryAsAny)

        assertThat(actualHistory).isEqualTo(DeployHistoryEntry.fromString(expected))
    }
}
