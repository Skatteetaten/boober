package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSuccess
import assertk.assertions.messageContains
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import io.mockk.clearAllMocks
import no.skatteetaten.aurora.boober.service.ApplicationDeploymentHerkimer
import no.skatteetaten.aurora.boober.service.ApplicationDeploymentPayload
import no.skatteetaten.aurora.boober.service.EmptyBodyException
import no.skatteetaten.aurora.boober.service.HerkimerResponse
import no.skatteetaten.aurora.boober.service.HerkimerRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.ResourceClaimHerkimer
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.herkimerObjectMapper
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.mockmvc.extensions.TestObjectMapperConfigurer
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.url
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

class HerkimerServiceTest {

    private val server = MockWebServer()
    val service = HerkimerService(
        client = HerkimerRestTemplateWrapper(RestTemplateBuilder().rootUri(server.url).build(), retries = 0)
    )

    @BeforeEach
    fun setup() {
        clearAllMocks()
        TestObjectMapperConfigurer.objectMapper = herkimerObjectMapper
    }

    @Test
    fun `test that empty body throws with nice message`() {
        val response = herkimerObjectMapper.createObjectNode()

        server.execute(HttpStatus.NO_CONTENT.value() to response) {
            assertThat { service.getClaimedResources("id", ResourceKind.ManagedOracleSchema) }
                .isFailure()
                .isInstanceOf(EmptyBodyException::class)
                .messageContains("empty body from Herkimer")
        }
    }

    @Test
    fun `Should create ApplicationDeployment`() {
        val adPayload = createAdPayload()

        val herkimerResponse = HerkimerResponse(items = listOf(
            adPayload.run {
                ApplicationDeploymentHerkimer(
                    "0123456789",
                    name,
                    environmentName,
                    cluster,
                    businessGroup,
                    applicationName,
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    "aurora",
                    "aurora"
                )
            }
        )
        )

        server.execute(herkimerResponse) {
            assertThat {
                service.createApplicationDeployment(adPayload)
            }.isSuccess().isEqualTo(herkimerResponse.items.single())
        }
    }

    @Test
    fun `Should throw error when trying to create ad and it returns success=false`() {
        val adPayload = createAdPayload()
        val failedHerkimerResponse = HerkimerResponse<ApplicationDeploymentHerkimer>(
            success = false,
            message = "Error happen",
            items = emptyList()
        )

        server.execute(failedHerkimerResponse) {
            assertThat { service.createApplicationDeployment(adPayload) }.isFailure()
                .isInstanceOf(ProvisioningException::class).given { ex ->
                    assertThat(ex.message).isNotNull().let {
                        it.contains("Unable to create ApplicationDeployment")
                        it.contains(failedHerkimerResponse.message)
                    }
                }
        }
    }

    @Test
    fun `Should return claimed resources`() {
        val adId = "019234abac5"
        val credentials = jsonMapper().convertValue<JsonNode>(mapOf("username" to "1234"))
        val herkimerResponse = HerkimerResponse(
            items = listOf(
                createResourceHerkimer(adId, credentials)
            )
        )

        server.execute(herkimerResponse) {
            assertThat { service.getClaimedResources(adId, ResourceKind.MinioPolicy) }.isSuccess()
                .given { listOfResources ->
                    assertThat(listOfResources.singleOrNull()).isNotNull().given { resource ->
                        assertThat(resource.ownerId).isEqualTo(adId)
                        assertThat(resource.claims?.singleOrNull()).isNotNull().given {
                            assertThat(it.credentials).isEqualTo(credentials)
                        }
                    }
                }
        }
    }

    @Test
    fun `Should throw when trying to get claimed resources and returns success=false`() {
        val herkimerResponse = HerkimerResponse<ResourceHerkimer>(
            success = false,
            message = "Failure",
            items = emptyList()
        )

        server.execute(herkimerResponse) {
            assertThat { service.getClaimedResources("019234abac5", ResourceKind.MinioPolicy) }
                .isFailure()
                .isInstanceOf(ProvisioningException::class).given { ex ->
                    assertThat(ex.message).isNotNull().let {
                        it.contains("Failure")
                        it.contains("Unable to get claimed resources")
                    }
                }
        }
    }

    @Test
    fun `Should return empty list for claimed resources when kind is not same as payload`() {
        val herkimerResponse = HerkimerResponse(
            items = listOf(
                createResourceHerkimer()
            )
        )

        server.execute(herkimerResponse) {
            assertThat {
                service.getClaimedResources("019234abac5", ResourceKind.ExternalSchema)
            }.isSuccess()
                .isEmpty()
        }
    }

    @Test
    fun `Should create resource and claim it`() {
        val createResourceResponse = HerkimerResponse(items = listOf(createResourceHerkimer()))
        val claimResourceResponse = HerkimerResponse<ResourceClaimHerkimer>(items = emptyList())

        server.execute(createResourceResponse, claimResourceResponse) {
            assertThat {
                service.createResourceAndClaim(
                    "019234abac5",
                    ResourceKind.MinioPolicy,
                    "myResource",
                    mapOf("username" to "1234")
                )
            }.isSuccess()
        }
    }

    @Test
    fun `Should throw when trying to claim resource and claiming fails with success=false`() {
        val createResourceResponse = HerkimerResponse(items = listOf(createResourceHerkimer()))
        val failedClaimResource =
            HerkimerResponse<ResourceClaimHerkimer>(success = false, message = "Could not claim resource", items = emptyList())

        server.execute(createResourceResponse, failedClaimResource) {
            assertThat {
                service.createResourceAndClaim(
                    "019234abac5",
                    ResourceKind.MinioPolicy,
                    "myResource",
                    mapOf("username" to "1234")
                )
            }.isFailure()
                .isInstanceOf(ProvisioningException::class)
                .given { ex ->
                    assertThat(ex.message).isNotNull().let {
                        it.contains("Could not claim")
                        it.contains("Unable to create claim")
                    }
                }
        }
    }

    @Test
    fun `Should throw when trying to create resource and it fails with success=false`() {
        val failedCreationOfResource =
            HerkimerResponse<ResourceHerkimer>(success = false, message = "Unable to create resource", items = emptyList())

        server.execute(failedCreationOfResource) {
            assertThat {
                service.createResourceAndClaim(
                    "019234abac5",
                    ResourceKind.MinioPolicy,
                    "myResource",
                    mapOf("username" to "1234")
                )
            }.isFailure()
                .isInstanceOf(ProvisioningException::class)
                .given { ex ->
                    assertThat(ex.message).isNotNull().let {
                        it.contains("Unable to create resource")
                        it.contains("type=${ResourceKind.MinioPolicy}")
                    }
                }
        }
    }

    private fun createResourceHerkimer(
        adId: String = "012345",
        credentials: JsonNode = jsonMapper().createObjectNode()
    ) = ResourceHerkimer(
        id = "0",
        name = "myResource",
        kind = ResourceKind.MinioPolicy,
        ownerId = adId,
        claims = listOf(
            ResourceClaimHerkimer(
                "0",
                adId,
                0L,
                credentials,
                LocalDateTime.now(),
                LocalDateTime.now(),
                "aurora",
                "aurora"
            )
        ),
        createdDate = LocalDateTime.now(),
        modifiedDate = LocalDateTime.now(),
        createdBy = "aurora",
        modifiedBy = "aurora"
    )

    private fun createAdPayload(): ApplicationDeploymentPayload = ApplicationDeploymentPayload(
        "testApp",
        "env",
        "utv",
        "aurora",
        "testApp"
    )
}
