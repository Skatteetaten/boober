package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.feature.azure.AzureFeature
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.utils.AbstractMultiFeatureTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

class AuroraAzureAppSubPartTest : AbstractMultiFeatureTest() {
    override val features: List<Feature>
        get() = listOf(
            WebsealFeature(".test.skead.no"),
            AzureFeature(cantusService, "0.4.0")
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
    }

    @Test
    fun `if minimal AuroraAzureApp is configured nothing goes wrong`() {
        val (_, auroraAzureApp) = generateResources(
            """{
             "azure" : {
                "azureAppFqdn": "saksmappa.amutv.skead.no",
                "groups": [] 
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 1
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app.json")
    }

    @Test
    fun `AuroraAzureApp can have clinger enabled`() {
        val (_, auroraAzureApp) = generateResources(
            """{
             "azure" : {
                "azureAppFqdn": "saksmappa.amutv.skead.no",
                "groups": [],
                "jwtToStsConverter": {
                    "enabled": true
                }
              }
           }""",
            mutableSetOf(createEmptyDeploymentConfig(), createEmptyDeploymentConfig()), createdResources = 1
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app-with-clinger.json")
    }

    @Test
    fun `2 elements configured, azureapp and clinger sidecar`() {
        val (_, auroraAzureApp) = generateResources(
            """{
              "azure": {
                "azureAppFqdn": "saksmappa.amutv.skead.no",
                "groups": [],
                "jwtToStsConverter": {
                  "discoveryUrl": "http://login-microsoftonline-com.app2ext.intern-preprod.skead.no/common/discovery/keys",
                  "enabled": true,
                  "version": "0.4.0"
                }
              }
        }""",
            createEmptyDeploymentConfig(), createdResources = 1
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app-with-clinger.json")
    }

    @Test
    fun `it is an error if only groups are present`() {
        Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            generateResources(
                """{
             "azure" : {
                "groups": [] 
              }
           }""",
                createEmptyDeploymentConfig(), createdResources = 0
            )
        }
    }

    @Test
    fun `it is an error if only azureAppFqdn are present`() {
        Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            generateResources(
                """{
             "azure" : {
                "azureAppFqdn": "a.b.c.d" 
              }
           }""",
                createEmptyDeploymentConfig(), createdResources = 0
            )
        }
    }

    @Test
    fun `AuroraAzureApp without clinger, but with webseal is OK`() {
        val (_, websealRoute, auroraAzureApp) = generateResources(
            """{
             "webseal": true,
             "azure" : {
                "azureAppFqdn": "saksmappa.amutv.skead.no",
                "groups": ["APP_dev", "APP_DRIFT"],
                "jwtToStsConverter": {
                    "enabled": false
                }
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 2
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app-with-webseal.json")
        assertThat(websealRoute).auroraResourceCreatedByTarget(WebsealFeature::class.java)
            .auroraResourceMatchesFile("webseal-route.json")

        // Trying to document that assert fails, not entirely sure if it is the best way:
        Assertions.assertThrows(AssertionFailedError::class.java) {
            assertThat(auroraAzureApp).auroraResourceCreatedByTarget(DatabaseFeature::class.java)
        }
    }

    @Test
    fun `AuroraAzureApp with webseal but without managedRoute is OK`() {
        val (_, websealRoute, auroraAzureApp) = generateResources(
            """{
              "webseal": {
                "host": "saksmappa",
                "roles": "APP_dev,APP_drift"
              },
              "azure": {
                "azureAppFqdn": "saksmappa.amutv.skead.no",
                "managedRoute": false,
                "groups": [
                  "APP_dev",
                  "APP_DRIFT"
                ]
              }
            }""",
            mutableSetOf(createEmptyDeploymentConfig(), createEmptyDeploymentConfig()), createdResources = 2
        )
        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app-with-webseal.json")
        assertThat(websealRoute).auroraResourceCreatedByTarget(WebsealFeature::class.java)
            .auroraResourceMatchesFile("webseal-saksmappe-route.json")
    }

    @Test
    fun `AuroraAzureApp with both managedRoute and webseal shall fail`() {
        Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            generateResources(
                """{
                  "webseal": true,
                  "azure": {
                    "azureAppFqdn": "saksmappa.amutv.skead.no",
                    "managedRoute": true,
                    "groups": [
                      "APP_dev",
                      "APP_DRIFT"
                    ]
                  }
                }""",
                mutableSetOf(createEmptyDeploymentConfig(), createEmptyDeploymentConfig()), createdResources = 2
            )
        }
    }

    @Test
    fun `AuroraAzureApp with both managedRoute and webseal shall fail - part II`() {
        Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            generateResources(
                """{
                  "webseal": {
                    "host": "saksmappa",
                    "roles": "APP_dev,APP_drift"
                  },
                  "azure": {
                    "azureAppFqdn": "saksmappa.amutv.skead.no",
                    "managedRoute": true,
                    "groups": [
                      "APP_dev",
                      "APP_DRIFT"
                    ]
                  }
                }""",
                mutableSetOf(createEmptyDeploymentConfig(), createEmptyDeploymentConfig()), createdResources = 2
            )
        }
    }

    @Test
    @Disabled(value = "This is WIP, disabled just to push buildable version of work")
    fun `AuroraAzureApp without clinger, with managedRoute, and no webseal is valid`() {
        val (_, auroraAzureApp, alternativeRoute) = generateResources(
            """{
              "azure": {
                "azureAppFqdn": "saksmappa.amutv.skead.no",
                "managedRoute": true,
                "groups": []
              }
            }""",
            mutableSetOf(createEmptyDeploymentConfig(), createEmptyDeploymentConfig()), createdResources = 1
        )
        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app.json")
        assertThat(alternativeRoute).auroraResourceCreatedByTarget(WebsealFeature::class.java)
            // WIP Possibly a differently formatted file:
            .auroraResourceMatchesFile("webseal-saksmappe-route.json")
    }
}
