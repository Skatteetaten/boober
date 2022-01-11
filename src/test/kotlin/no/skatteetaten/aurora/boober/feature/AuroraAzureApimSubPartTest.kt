package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import assertk.assertThat
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.feature.azure.AzureFeature
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.utils.AbstractMultiFeatureTest

class AuroraAzureApimSubPartTest : AbstractMultiFeatureTest() {
    override val features: List<Feature>
        get() = listOf(
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
    fun `if minimal AuroraAzureApim is configured nothing goes wrong`() {
        val (_, auroraAzureApp) = generateResources(
            """{
             "azure" : {
                "apim": {
                    "enabled"    : true,
                    "path"       : "/unique",      
                    "openapiUrl" : "https://openapi",
                    "serviceUrl" : "https://service",
                    "policies"   : {},  
                    "apiHost"    : "api.apimanagement.dev.skatteetaten.io"   
                }
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 1
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-apim.json")
    }

    @Test
    fun `if disabled policies are not taken into consideration`() {
        val (_, auroraAzureApp) = generateResources(
            """{
             "azure" : {
                "apim": {
                    "enabled"    : true,
                    "path"       : "/unique",      
                    "openapiUrl" : "https://openapi",
                    "serviceUrl" : "https://service",
                    "policies"   : {"justDisable":false, "disabledToo": false},  
                    "apiHost"    : "api.apimanagement.dev.skatteetaten.io"   
                }
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 1
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-apim.json")
    }

    @Test
    fun `that policies need to boolean type boolean`() {
        Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            generateResources(
                """{
             "azure" : {
                "apim": {
                    "enabled"    : true,
                    "path"       : "/unique",      
                    "openapiUrl" : "https://openapi",
                    "serviceUrl" : "https://service",
                    "policies"   : {"justDisable": "string-value-is-intentionally-wrong"},  
                    "apiHost"    : "api.apimanagement.dev.skatteetaten.io"   
                }
              }
           }""",
                createEmptyDeploymentConfig(), createdResources = 1
            )
        }
    }

    @Test
    fun `if apim is declared, you need to declare policies explicitly`() {
        Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            generateResources(
                """{
             "azure" : {
                "apim": {
                    "enabled"    : true,
                    "path"       : "/unique",      
                    "openapiUrl" : "https://openapi",
                    "serviceUrl" : "https://service",
                    "apiHost"    : "api.apimanagement.dev.skatteetaten.io"   
                }
              }
           }""",
                createEmptyDeploymentConfig(), createdResources = 1
            )
        }
    }

    @Test
    fun `if AuroraAzureApim is disabled, no resource is created`() {
        val (_) = generateResources(
            """{
             "azure" : {
                "apim": {
                    "enabled"    : false,
                    "path"       : "/unique",      
                    "openapiUrl" : "https://openapi",
                    "serviceUrl" : "https://service",
                    "policies"   : {},  
                    "apiHost"    : "api.apimanagement.dev.skatteetaten.io"   
                }
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 0
        )
    }

    @Test
    fun `invalid path gives error`() {
        Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            generateResources(
                """{
             "azure" : {
                "apim": {
                    "enabled"    : true,
                    "path"       : "invalid-if-it-does-not-start-with-slash",      
                    "openapiUrl" : "https://openapi",
                    "serviceUrl" : "https://service",
                    "policies"   : {},  
                    "apiHost"    : "api.apimanagement.dev.skatteetaten.io"   
                }
              }
           }""",
                createEmptyDeploymentConfig(), createdResources = 1
            )
        }
    }

    @Test
    fun `3 elements configured, azureapp, clinger sidecar and azureapim`() {
        val (_, _, auroraApim) = generateResources(
            """{
              "azure": {
                "azureAppFqdn": "saksmappa.amutv.skead.no",
                "groups": [],
                "jwtToStsConverter": {
                  "discoveryUrl": "http://login-microsoftonline-com.app2ext.intern-preprod.skead.no/common/discovery/keys",
                  "enabled": true,
                  "version": "0.4.0"
                },
                "apim": {
                  "enabled"    : true,
                  "path"       : "/unique",      
                  "openapiUrl" : "https://openapi",
                  "serviceUrl" : "https://service",
                  "policies"   : {},  
                  "apiHost"    : "api.apimanagement.dev.skatteetaten.io"   
                }
              }
        }""",
            createEmptyDeploymentConfig(), createdResources = 2
        )

        assertThat(auroraApim).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-apim.json")
    }

    @Test
    fun `that policies can be defined`() {
        val (_, auroraAzureApp) = generateResources(
            """{
             "azure" : {
                "apim": {
                    "enabled"    : true,
                    "path"       : "/unique",      
                    "openapiUrl" : "https://openapi",
                    "serviceUrl" : "https://service",
                    "policies"   : {
                        "policy-a": true,
                        "policy-b": true
                    },  
                    "apiHost"    : "api.apimanagement.dev.skatteetaten.io"   
                }
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 1
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-apim-with-policies.json")
    }
}
