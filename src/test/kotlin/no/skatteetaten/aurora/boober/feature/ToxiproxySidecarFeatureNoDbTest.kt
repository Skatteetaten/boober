package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.AbstractMultiFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock

class ToxiproxySidecarFeatureNoDbTest : AbstractMultiFeatureTest() {

    override val features: List<Feature>
        get() = listOf(
            ConfigFeature(),
            DatabaseDisabledFeature("utv"),
            ToxiproxySidecarFeature(cantusService, null, userDetailsProvider, "2.1.3")
        )

    private val cantusService: CantusService = mockk()

    private val userDetailsProvider: UserDetailsProvider = mockk()

    @BeforeEach
    fun setupMock() {
        every {
            cantusService.getImageMetadata(
                "shopify", "toxiproxy", "2.1.3"
            )
        } returns
            ImageMetadata(
                "docker.registry/shopify/toxiproxy",
                "2.1.3",
                "sha:1234"
            )

        every { userDetailsProvider.getAuthenticatedUser() } returns User("username", "token")
    }

    @AfterEach
    fun clearMocks() {
        HttpMock.clearAllHttpMocks()
    }

    @Test
    fun `should add toxiproxy to dc and change service port when DatabaseFeature is disabled`() {

        val (serviceResource, dcResource, configResource) = generateResources(
            """{
                "toxiproxy" : true 
            }""",
            createEmptyService(),
            createEmptyDeploymentConfig()
        )

        assertThat(serviceResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Changed targetPort to point to toxiproxy",
            )

        val service = serviceResource.resource as Service
        assertThat(service.spec.ports.first().targetPort).isEqualTo(IntOrString(PortNumbers.TOXIPROXY_HTTP_PORT))

        assertThat(dcResource)
            .auroraResourceModifiedByFeatureWithComment(
                feature = ToxiproxySidecarFeature::class.java,
                comment = "Added toxiproxy volume and sidecar container",
            )
            .auroraResourceMatchesFile("dc.json")

        assertThat(configResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("config.json")
    }

    @Test
    fun `should return error when database is configured for Toxiproxy when DatabaseFeature is disabled`() {

        assertThat {
            generateResources(
                """{
                    "toxiproxy": {
                        "proxies": {
                            "dbProxy": {"database": true}
                        }
                    },
                    "database": true
                }"""
            )
        }.singleApplicationError("Databases are not supported in this cluster")
    }
}
