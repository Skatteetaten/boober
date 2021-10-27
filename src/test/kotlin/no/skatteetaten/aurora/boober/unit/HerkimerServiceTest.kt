package no.skatteetaten.aurora.boober.unit

import java.time.LocalDateTime
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSuccess
import assertk.assertions.messageContains
import io.mockk.clearAllMocks
import no.skatteetaten.aurora.boober.service.ApplicationDeploymentCreateRequest
import no.skatteetaten.aurora.boober.service.ApplicationDeploymentHerkimer
import no.skatteetaten.aurora.boober.service.HerkimerConfiguration
import no.skatteetaten.aurora.boober.service.HerkimerResponse
import no.skatteetaten.aurora.boober.service.HerkimerRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.ResourceClaimHerkimer
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.objectMapperWithTime
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.mockmvc.extensions.TestObjectMapperConfigurer
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.url
import okhttp3.mockwebserver.MockWebServer

class HerkimerServiceTest {

    private val server = MockWebServer()
    val service = HerkimerService(
        client = HerkimerRestTemplateWrapper(
            RestTemplateBuilder().rootUri(server.url).build(),
            HerkimerConfiguration(retries = 0, url = "")
        )
    )

    @BeforeEach
    fun setup() {
        clearAllMocks()
        TestObjectMapperConfigurer.objectMapper = objectMapperWithTime
    }

    @Test
    fun `test that empty body throws with nice message`() {
        val response = objectMapperWithTime.createObjectNode()

        server.execute(HttpStatus.NO_CONTENT.value() to response) {
            assertThat { service.getClaimedResources("id", ResourceKind.ManagedOracleSchema) }
                .isFailure()
                .isInstanceOf(ProvisioningException::class)
                .messageContains("cause=empty body")
        }
    }

    @Test
    fun `Should create ApplicationDeployment`() {
        val adPayload = createAdPayload()

        val herkimerResponse = HerkimerResponse(
            items = listOf(
                adPayload.run {
                    ApplicationDeploymentHerkimer(
                        "0123456789",
                        name,
                        environmentName,
                        cluster,
                        businessGroup,
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
            assertThat { service.getClaimedResources(adId, ResourceKind.MinioPolicy, name = "bucketname") }.isSuccess()
                .given { listOfResources ->
                    assertThat(listOfResources.singleOrNull()).isNotNull().given { resource ->

                        assertThat(resource.ownerId).isEqualTo(adId)

                        assertThat(resource.claims.singleOrNull()?.credentials)
                            .isNotNull()
                            .isEqualTo(credentials)
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
    fun `Should create resource and claim it`() {
        val createResourceResponse = HerkimerResponse(items = listOf(createResourceHerkimer()))
        val claimResourceResponse = HerkimerResponse<ResourceClaimHerkimer>(items = emptyList())

        server.execute(createResourceResponse, claimResourceResponse) {
            assertThat {
                service.createResourceAndClaim(
                    "019234abac5",
                    ResourceKind.MinioPolicy,
                    "myResource",
                    "ADMIN",
                    mapOf("username" to "1234")
                )
            }.isSuccess()
        }
    }

    @Test
    fun `Should throw when trying to claim resource and claiming fails with success=false`() {
        val createResourceResponse = HerkimerResponse(items = listOf(createResourceHerkimer()))
        val failedClaimResource =
            HerkimerResponse<ResourceClaimHerkimer>(
                success = false,
                message = "Could not claim resource",
                items = emptyList()
            )

        server.execute(createResourceResponse, failedClaimResource) {
            assertThat {
                service.createResourceAndClaim(
                    "019234abac5",
                    ResourceKind.MinioPolicy,
                    "myResource",
                    "ADMIN",
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
            HerkimerResponse<ResourceHerkimer>(
                success = false,
                message = "Unable to create resource",
                items = emptyList()
            )

        server.execute(failedCreationOfResource) {
            assertThat {
                service.createResourceAndClaim(
                    "019234abac5",
                    ResourceKind.MinioPolicy,
                    "myResource",
                    "ADMIN",
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
                id = "0",
                ownerId = adId,
                resourceId = 0L,
                credentials = credentials,
                name = "ADMIN",
                createdDate = LocalDateTime.now(),
                modifiedDate = LocalDateTime.now(),
                createdBy = "aurora",
                modifiedBy = "aurora"
            )
        ),
        createdDate = LocalDateTime.now(),
        modifiedDate = LocalDateTime.now(),
        createdBy = "aurora",
        modifiedBy = "aurora",
        parentId = null
    )

    private fun createAdPayload() = ApplicationDeploymentCreateRequest(
        name = "testApp",
        environmentName = "env",
        cluster = "utv",
        businessGroup = "aurora"
    )
}
