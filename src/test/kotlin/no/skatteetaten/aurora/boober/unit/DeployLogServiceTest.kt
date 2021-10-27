package no.skatteetaten.aurora.boober.unit

import java.nio.charset.Charset
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.client.HttpClientErrorException
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.BitbucketService
import no.skatteetaten.aurora.boober.service.DeployHistoryEntry
import no.skatteetaten.aurora.boober.service.DeployLogService
import no.skatteetaten.aurora.boober.service.DeployLogServiceException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.boober.utils.openshiftKind
import no.skatteetaten.aurora.boober.utils.stubDeployResult

class DeployLogServiceTest : ResourceLoader() {

    private val bitbucketService = mockk<BitbucketService>()
    private val deployId = "12e456"
    private val fileName = "test/$deployId.json"

    private val userDetailsProvider = mockk<UserDetailsProvider>()

    private val service = DeployLogService(
        bitbucketService = bitbucketService,
        mapper = jsonMapper(),
        project = "ao",
        repo = "auroradeploymenttags",
        userDetailsProvider = userDetailsProvider
    )

    @AfterEach
    fun tearDown() {
        clearMocks(bitbucketService)
    }

    @BeforeEach
    fun setup() {
        every { userDetailsProvider.getAuthenticatedUser() } returns User(
            "hero", "token", "Jayne Cobb",
            grantedAuthorities = listOf(
                SimpleGrantedAuthority("APP_PaaS_utv"), SimpleGrantedAuthority("APP_PaaS_drift")
            )
        )
    }

    @Test
    fun `Should mark release`() {
        every {
            bitbucketService.uploadFile("ao", "auroradeploymenttags", fileName, "DEPLOY/utv-utv/simple", any())
        } returns "Success"

        val response = service.markRelease(stubDeployResult(deployId))

        verify(exactly = 1) {
            bitbucketService.uploadFile("ao", "auroradeploymenttags", fileName, "DEPLOY/utv-utv/simple", any())
        }
        assertThat(response.size).isEqualTo(1)
        assertThat(actual = response.first().bitbucketStoreResult).isEqualTo("Success")
    }

    @Test
    fun `Should filter out secrets`() {
        val deployHistoryEntrySlotAsJson = slot<String>()
        every {
            bitbucketService.uploadFile(
                "ao",
                "auroradeploymenttags",
                fileName,
                "DEPLOY/utv-utv/simple",
                capture(deployHistoryEntrySlotAsJson)
            )
        } returns "Success"

        val openshiftResponsesJson = loadJsonResource("openshift-responses-with-secret.json")
        val openshiftResponse = jacksonObjectMapper().convertValue<List<OpenShiftResponse>>(openshiftResponsesJson)
        val response = service.markRelease(stubDeployResult(deployId, openshiftResponses = openshiftResponse))

        verify(exactly = 1) {
            bitbucketService.uploadFile("ao", "auroradeploymenttags", fileName, "DEPLOY/utv-utv/simple", any())
        }

        assertThat(response.size).isEqualTo(1)

        assertThat(response.first().bitbucketStoreResult).isEqualTo("Success")

        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

        val deployHistoryEntry = mapper.readValue<DeployHistoryEntry>(deployHistoryEntrySlotAsJson.captured)
        val result = deployHistoryEntry.result.openshift
        val kindInOpenshiftResponses = result.mapNotNull { it.responseBody?.openshiftKind }
        val payloadKindInOpenshiftResponse = result.map { it.command.payload.openshiftKind }
        val previousKindInOpenshiftResponse = result.mapNotNull { it.command.previous }
            .filter { it !is NullNode }
            .map { it.openshiftKind }

        assertThat(kindInOpenshiftResponses).doesNotContain("secret")
        assertThat(payloadKindInOpenshiftResponse).doesNotContain("secret")
        assertThat(previousKindInOpenshiftResponse).doesNotContain("secret")
    }

    @Test
    fun `Should mark failed release`() {
        every {
            bitbucketService.uploadFile("ao", "auroradeploymenttags", fileName, "DEPLOY/utv-utv/simple", any())
        } throws RuntimeException("Some really bad stuff happened")

        val response = service.markRelease(stubDeployResult(deployId))

        assertThat(response.size).isEqualTo(1)
        val answer = response.first()

        assertThat(answer.bitbucketStoreResult).isEqualTo("Some really bad stuff happened")
        assertThat(answer.reason).isEqualTo("DONE Failed to store deploy result.")
        assertThat(answer.deployId).isEqualTo(deployId)
    }

    @Test
    fun `Find deploy result by id throws DeployLogServiceException when git file not found`() {
        every { bitbucketService.getFile(any(), any(), any()) } throws
            HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "",
                HttpHeaders(),
                "404 ".toByteArray(),
                Charset.defaultCharset()
            )

        assertThat {
            service.findDeployResultById(AuroraConfigRef("test", "master", "123"), "abc123")
        }.isNotNull().isFailure().isInstanceOf(DeployLogServiceException::class)
    }

    @Test
    fun `Find deploy result by id throws HttpClientErrorException given bad request`() {
        every { bitbucketService.getFile(any(), any(), any()) } throws
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "",
                HttpHeaders(),
                "400".toByteArray(),
                Charset.defaultCharset()
            )

        assertThat {
            service.findDeployResultById(AuroraConfigRef("test", "master", "123"), "abc123")
        }.isNotNull().isFailure().isInstanceOf(HttpClientErrorException::class)
    }
}
