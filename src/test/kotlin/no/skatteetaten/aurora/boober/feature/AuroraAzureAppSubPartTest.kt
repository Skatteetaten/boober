package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.feature.azure.AzureFeature
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeploymentContextService
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.IdService
import no.skatteetaten.aurora.boober.service.IdServiceFallback
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.utils.AbstractMultiFeatureTest
import no.skatteetaten.aurora.boober.utils.AuroraConfigSamples.Companion.createAuroraConfig
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.opentest4j.AssertionFailedError

class AuroraAzureAppSubPartTest : AbstractMultiFeatureTest() {
    private val cantusService: CantusService = mockk()
    private val azureFeature = AzureFeature(cantusService, "0", "ldap://default", "http://jwks")

    override val features: List<Feature>
        get() {
            return listOf(
                WebsealFeature(".test.skead.no"),
                azureFeature
            )
        }

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
    fun `it is ignored if only groups are present`() {
        generateResources(
            """{
             "azure" : {
                "groups": [] 
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 0
        )
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

    @Test
    fun `AuroraAzureApp should be turned off by false`() {
        val (_) = generateResources(
            """{
              "azure": false
              }
            }""",
            mutableSetOf(createEmptyDeploymentConfig(), createEmptyDeploymentConfig()), createdResources = 0
        )
    }

    @Test
    fun `AuroraAzureApp should ignore just azure set to true`() {
        val (_) = generateResources(
            """{
              "azure": true
              }
            }""",
            mutableSetOf(createEmptyDeploymentConfig(), createEmptyDeploymentConfig()), createdResources = 0
        )
    }

    @Test
    fun `validate azure false in sub resource should not complain about missing azureFqdn`() {
        val idService = mockk<IdService>().also {
            every { it.generateOrFetchId(any()) } returns "1234567890"
        }

        val idServiceFallback = mockk<IdServiceFallback>().also {
            every { it.generateOrFetchId(any(), any()) } returns "fallbackid"
        }

        val service = AuroraDeploymentContextService(
            features = features,
            idService = idService,
            idServiceFallback = idServiceFallback
        )

        val localConfig = mutableMapOf(
            "about.json" to FEATURE_ABOUT,
            "$environment/about.json" to """{ }""",
            "$appName.json" to """{
              "azure": {
                "groups": [],
                "jwtToStsConverter": {
                  "enabled": true
                }
              }
            }
            """.trimIndent(),
            "$environment/$appName.json" to """{ 
                "azure": false
            }
            """.trimIndent()
        )

        val deployCommand = AuroraContextCommand(
            auroraConfig = createAuroraConfig(localConfig),
            applicationDeploymentRef = aid,
            auroraConfigRef = AuroraConfigRef("test", "master", "123abb"),
        )

        val contexts = service.createValidatedAuroraDeploymentContexts(listOf(deployCommand), true)

        val validContexts = contexts.first
        assertThat(validContexts).isNotNull()
        assertThat(validContexts.size).isGreaterThan(0)
        assertThat(azureFeature.isActive(contexts.first[0].spec)).isFalse()
    }
}
