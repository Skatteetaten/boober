package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.feature.azure.AzureFeature
import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.utils.AbstractMultiFeatureTest
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JwtToStsConverterSubPartTest : AbstractMultiFeatureTest() {
    override val features: List<Feature>
        get() = listOf(
            AzureFeature(cantusService, "0.4.0", "ldap://default"),
            WebsealFeature(".test.skead.no")
        )

    private val cantusService: CantusService = mockk()

    @BeforeEach
    fun setupMock() {
        every {
            cantusService.getImageMetadata(
                "no_skatteetaten_aurora", "clinger", "0.4.0"
            )
        } returns
            ImageMetadata(
                "docker.registry/no_skatteetaten_aurora/clinger",
                "0.4.0",
                "sha:1234567"
            )
        every {
            cantusService.getImageMetadata(
                "no_skatteetaten_aurora", "clinger", "0.3.2"
            )
        } returns
            ImageMetadata(
                "docker.registry/no_skatteetaten_aurora/clinger",
                "0.3.2",
                "sha:11223344"
            )
    }

    @AfterEach
    fun clearMocks() {
        HttpMock.clearAllHttpMocks()
    }

    @Test
    fun `no explisit version gives default`() {
        val (_, dcResource) = modifyResources(
            """{
             "azure" : {
                "jwtToStsConverter": {
                    "enabled": true,
                    "discoveryUrl": "https://endpoint",
                    "ivGroupsRequired": "false"
                }
              }
           }""",
            createEmptyService(), createEmptyDeploymentConfig()
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added clinger sidecar container")
            .auroraResourceMatchesFile("dc.json")
    }

    @Test
    fun `should add clinger to dc and change service port`() {
        val (serviceResource, dcResource) = modifyResources(
            """{
             "azure" : {
                "jwtToStsConverter": {
                    "enabled": true,
                    "version": "0.4.0", 
                    "discoveryUrl": "https://endpoint",
                    "ivGroupsRequired": "false"
                }
              }
           }""",
            createEmptyService(), createEmptyDeploymentConfig()
        )

        assertThat(serviceResource).auroraResourceModifiedByThisFeatureWithComment("Changed targetPort to point to clinger")
        val service = serviceResource.resource as Service
        assertEquals(
            service.spec.ports.first().targetPort,
            IntOrString(PortNumbers.CLINGER_PROXY_SERVER_PORT),
            "Target should be rewritten to proxy port."
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added clinger sidecar container")
            .auroraResourceMatchesFile("dc.json")
    }

    @Test
    fun `can override version`() {
        val (_, dcResource) = modifyResources(
            """{
             "azure" : {
                "jwtToStsConverter": {
                    "enabled": true,
                    "version": "0.3.2", 
                    "discoveryUrl": "https://endpoint",
                    "ivGroupsRequired": "false"
                }
              }
           }""",
            createEmptyService(), createEmptyDeploymentConfig()
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added clinger sidecar container")
            .auroraResourceMatchesFile("updated-version-dc.json")
    }

    @Test
    fun `clinger is able to be enabled based on default values`() {
        val (serviceResource, dcResource) = modifyResources(
            """{
             "azure" : {
                "jwtToStsConverter": {
                    "enabled": true
                }
              }
           }""",
            createEmptyService(), createEmptyDeploymentConfig()
        )

        assertThat(serviceResource).auroraResourceModifiedByThisFeatureWithComment("Changed targetPort to point to clinger")
        val service = serviceResource.resource as Service
        assertEquals(
            service.spec.ports.first().targetPort,
            IntOrString(PortNumbers.CLINGER_PROXY_SERVER_PORT),
            "Target should be rewritten to proxy port."
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added clinger sidecar container")
            .auroraResourceMatchesFile("dc-wo-discovery.json")
    }

    @Test
    fun `clinger is not confused by AzureAppConfig`() { // x
        val (_, dcResource, azureApp) = generateResources(
            """{
             "azure" : {
                "azureAppFqdn": "saksmappa.amutv.skead.no",
                "groups": [],
                "jwtToStsConverter": {
                    "enabled": true,
                    "discoveryUrl": "https://endpoint"
                }
              }
           }""",
            createdResources = 1, resources = mutableSetOf(createEmptyService(), createEmptyDeploymentConfig())
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added clinger sidecar container")
            .auroraResourceMatchesFile("dc.json")
        assertThat(azureApp).auroraResourceMatchesFile("auroraazureapp.json")
    }

    @Test
    fun `clinger can have webseal enabled`() {
        val (_, dcResource, webseal) = generateResources(
            """{
             "webseal": true,
             "azure" : {
                "jwtToStsConverter": {
                    "enabled": true,
                    "discoveryUrl": "https://endpoint"
                }
              }
           }""",
            createdResources = 1, resources = mutableSetOf(createEmptyService(), createEmptyDeploymentConfig())
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added clinger sidecar container")
            .auroraResourceMatchesFile("dc-webseal-true.json")
        assertThat(webseal).auroraResourceMatchesFile("webseal-route.json")
    }

    @Test
    fun `clinger can have iv-groups enabled`() {

        val (_, dcResource) = modifyResources(
            """{
             "azure" : {
                "jwtToStsConverter": {
                    "enabled": true,
                    "discoveryUrl": "https://endpoint",
                    "ivGroupsRequired": true,
                    "ldapUserSecretRef": "foo-vault"
                }
              }
           }""",
            createEmptyService(), createEmptyDeploymentConfig()
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added clinger sidecar container")
            .auroraResourceMatchesFile("dc-ivgroups-true.json")
    }
}
