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
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.opentest4j.AssertionFailedError

class AuroraAzureAppSubPartTest : AbstractMultiFeatureTest() {
    override val features: List<Feature>
        get() = listOf(
            WebsealFeature(".test.skead.no"),
            AzureFeature(cantusService, "0", "ldap://default", "http://jwks")
        )

    private val cantusService: CantusService = mockk()

    @BeforeEach
    fun setupMock() {
        every {
            cantusService.getImageMetadata(
                "no_skatteetaten_aurora", "clinger", "0"
            )
        } returns
            ImageMetadata(
                "docker.registry/no_skatteetaten_aurora/clinger",
                "0",
                "sha:1234567"
            )
    }

    @Test
    fun `if minimal AuroraAzureApp is configured nothing goes wrong`() {
        val (_, auroraAzureApp, managedRoute) = generateResources(
            """{
             "azure" : {
                "azureAppFqdn": "tjeneste-foo.amutv.skead.no",
                "groups": [] 
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 2
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app.json")
        assertThat(managedRoute).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-managed-route.json")
    }

    @Test
    fun `if AuroraAzureApp is configured with placeholders nothing goes wrong`() {
        val (_, auroraAzureApp, managedRoute) = generateResources(
            """{
             "azure" : {
                "azureAppFqdn": "tjeneste-foo-@env@.amutv.skead.no",
                "groups": [] 
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 2
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app-with-env.json")
        assertThat(managedRoute).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-managed-route-with-env.json")
    }

    @Test
    fun `AuroraAzureApp can have clinger enabled`() {
        val (_, auroraAzureApp, managedRoute) = generateResources(
            """{
             "azure" : {
                "azureAppFqdn": "tjeneste-foo.amutv.skead.no",
                "groups": [],
                "jwtToStsConverter": {
                    "enabled": true
                }
              }
           }""",
            mutableSetOf(createEmptyDeploymentConfig(), createEmptyDeploymentConfig()), createdResources = 2
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app-with-clinger.json")
        assertThat(managedRoute).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-managed-route.json")
    }

    @Test
    fun `2 elements configured, azureapp and clinger sidecar`() {
        val (_, auroraAzureApp, managedRoute) = generateResources(
            """{
              "azure": {
                "azureAppFqdn": "tjeneste-foo.amutv.skead.no",
                "groups": [],
                "jwtToStsConverter": {
                  "enabled": true
                }
              }
        }""",
            createEmptyDeploymentConfig(), createdResources = 2
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app-with-clinger.json")
        assertThat(managedRoute).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-managed-route.json")
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
                "azureAppFqdn": "valid.amutv.skead.no" 
              }
           }""",
                createEmptyDeploymentConfig(), createdResources = 0
            )
        }
    }

        @ParameterizedTest
    @CsvSource("invalid", "invalid.notam.skead.no", "multiple.dots.amutv.skead.no", "invalid.skead.no")
    fun `it is an error if azureAppFqdn is not a valid fqdn for azure`(fqdn: String) {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{
             "azure" : {
                "azureAppFqdn": "$fqdn",
                "groups": ["APP_dev", "APP_DRIFT"]
              }
           }"""
            )
        }.singleApplicationError("must be a fully qualified domain name ")
    }

    @CsvSource("invalid", "@foo", "invalid.notam.skead.no", "multiple.dots.amutv.skead.no", "invalid.skead.no")
    fun `it is an error if azureAppFqdn is not a valid fqdn for azure`(fqdn: String) {
        Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            generateResources(
                """{
             "azure" : {
                "azureAppFqdn": "$fqdn" 
              }
           }""",
                createEmptyDeploymentConfig(), createdResources = 0
            )
        }
    }

    @Test
    fun `AuroraAzureApp without clinger, but with webseal is OK`() {
        val (_, websealRoute, auroraAzureApp, managedRoute) = generateResources(
            """{
             "webseal": true,
             "azure" : {
                "azureAppFqdn": "tjeneste-foo.amutv.skead.no",
                "groups": ["APP_dev", "APP_DRIFT"],
                "jwtToStsConverter": {
                    "enabled": false
                }
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 3
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app-with-webseal.json")
        assertThat(websealRoute).auroraResourceCreatedByTarget(WebsealFeature::class.java)
            .auroraResourceMatchesFile("webseal-route.json")
        assertThat(managedRoute).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-managed-route.json")

        // Trying to document that assert fails:
        Assertions.assertThrows(AssertionFailedError::class.java) {
            assertThat(auroraAzureApp).auroraResourceCreatedByTarget(DatabaseFeature::class.java)
        }
    }

    @Test
    fun `AuroraAzureApp with webseal is OK`() {
        val (_, websealRoute, auroraAzureApp, managedRoute) = generateResources(
            """{
              "webseal": {
                "host": "tjeneste-foo",
                "roles": "APP_dev,APP_drift"
              },
              "azure": {
                "azureAppFqdn": "tjeneste-foo.amutv.skead.no",
                "groups": [
                  "APP_dev",
                  "APP_DRIFT"
                ]
              }
            }""",
            mutableSetOf(createEmptyDeploymentConfig(), createEmptyDeploymentConfig()), createdResources = 3
        )
        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app-with-webseal.json")
        assertThat(websealRoute).auroraResourceCreatedByTarget(WebsealFeature::class.java)
            .auroraResourceMatchesFile("webseal-saksmappe-route.json")
        assertThat(managedRoute).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-managed-route.json")
    }

    @Test
    fun `AuroraAzureApp without clinger, with managedRoute, and no webseal is valid`() {
        val (_, auroraAzureApp, alternativeRoute) = generateResources(
            """{
              "azure": {
                "azureAppFqdn": "tjeneste-foo.amutv.skead.no",
                "groups": []
              }
            }""",
            mutableSetOf(createEmptyDeploymentConfig(), createEmptyDeploymentConfig()), createdResources = 2
        )
        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app.json")
        assertThat(alternativeRoute).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-managed-route.json")
    }

    @Test
    fun `AuroraAzureApp with managedRoute and clusterTimeout is valid`() {
        val (_, auroraAzureApp, alternativeRoute) = generateResources(
            """{
              "azure": {
                "azureAppFqdn": "tjeneste-foo.amutv.skead.no",
                "clusterTimeout": "50s",
                "groups": []
              }
            }""",
            mutableSetOf(createEmptyDeploymentConfig(), createEmptyDeploymentConfig()), createdResources = 2
        )
        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-app.json")
        assertThat(alternativeRoute).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-route-timeout.json")
    }
}
