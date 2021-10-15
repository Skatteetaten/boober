package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class AuroraAzureAppFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = AuroraAzureAppFeature(JwtToStsConverterFeature(cantusService, "0.3.1"))

    private val cantusService: CantusService = mockk()

    @Test
    fun `if minimal AuroraAzureApp is configured nothing goes wrong`() {
        val (_, auroraAzureApp) = generateResources(
            """{
             "azure" : {
                "azureAppFqdn": "saksmappa.amutv.skead.no",
                "groups": [] 
              }
           }""", createEmptyDeploymentConfig(), createdResources = 1
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByThisFeature()
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
           }""", createEmptyDeploymentConfig(), createdResources = 1
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByThisFeature()
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
                  "version": "0.3.1"
                }
              }
        }""", createEmptyDeploymentConfig(), createdResources = 1
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByThisFeature()
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
           }""", createEmptyDeploymentConfig(), createdResources = 0
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
           }""", createEmptyDeploymentConfig(), createdResources = 0
            )
        }
    }
}
