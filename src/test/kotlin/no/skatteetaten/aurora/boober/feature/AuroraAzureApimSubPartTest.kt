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
                    "apiName"    : "super-api",
                    "version"    : "v1", 
                    "path"       : "/path/to/api",
                    "openApiUrl" : "https://openapi",
                    "serviceUrl" : "https://service"
                }
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 1
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-apim.json")
    }

    @Test
    fun `if policies are filtered, added and sorted`() {
        val (_, auroraAzureApp) = generateResources(
            """{
             "azure" : {
                "apim": {
                    "enabled"    : true,
                    "apiName"    : "super-api",
                    "version"    : "v1", 
                    "path"       : "/path/to/api",
                    "openApiUrl" : "https://openapi",
                    "serviceUrl" : "https://service",
                    "policies"   : {
                                        "policy2": {
                                            "enabled": true,
                                            "parameters": {
                                                "param1": "value1",
                                                "param2": "value2"
                                            }
                                         },
                                        "policy1": {
                                            "enabled": true
                                         },
                                        "policy3": {
                                            "enabled": false
                                         }
                                    }
                }
              }
           }""",
            createEmptyDeploymentConfig(), createdResources = 1
        )

        assertThat(auroraAzureApp).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-apim-with-policies.json")
    }

    @Test
    fun `if policies must be a list`() {
        Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            generateResources(
                """{
             "azure" : {
                "apim": {
                    "enabled"    : true,
                    "apiName"    : "super-api",
                    "version"    : "v1", 
                    "path"       : "path/to/api",
                    "openApiUrl" : "https://openapi",
                    "serviceUrl" : "https://service",
                    "policies"   : "awsome policy"
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
                    "apiName"    : "super-api",
                    "version"    : "v1", 
                    "path"       : "/path/to/api",
                    "openApiUrl" : "https://openapi",
                    "serviceUrl" : "https://service"
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
                    "apiName"    : "super-api",
                    "version"    : "v1", 
                    "path"       : "/should/not/end/with/slash/",
                    "openApiUrl" : "https://openapi",
                    "serviceUrl" : "https://service"
                }
              }
           }""",
                createEmptyDeploymentConfig(), createdResources = 1
            )
        }
    }

    @Test
    fun `invalid version gives error`() {
        Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            generateResources(
                """{
             "azure" : {
                "apim": {
                    "enabled"    : true,
                    "apiName"    : "super-api",
                    "version"    : "versjon1", 
                    "path"       : "path",
                    "openApiUrl" : "https://openapi",
                    "serviceUrl" : "https://service"
                }
              }
           }""",
                createEmptyDeploymentConfig(), createdResources = 1
            )
        }
    }

    @Test
    fun `invalid openApiUrl gives error`() {
        Assertions.assertThrows(MultiApplicationValidationException::class.java) {
            generateResources(
                """{
             "azure" : {
                "apim": {
                    "enabled"    : true,
                    "apiName"    : "super-api",
                    "version"    : "v1", 
                    "path"       : "/path",
                    "openApiUrl" : "openapi",
                    "serviceUrl" : "https://service"
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
                    "apiName"    : "super-api",
                    "version"    : "v1", 
                    "path"       : "/path/to/api",
                    "openApiUrl" : "https://openapi",
                    "serviceUrl" : "https://service"
                }
              }
        }""",
            createEmptyDeploymentConfig(), createdResources = 2
        )

        assertThat(auroraApim).auroraResourceCreatedByTarget(AzureFeature::class.java)
            .auroraResourceMatchesFile("aurora-azure-apim.json")
    }
}
