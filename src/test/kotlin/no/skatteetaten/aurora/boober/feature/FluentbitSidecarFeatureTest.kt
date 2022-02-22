package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.feature.fluentbit.FluentbitSidecarFeature
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationErrorResult
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock

class FluentbitSidecarFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = FluentbitSidecarFeature(
            cantusService,
            "test_hec",
            "splunk.url",
            "8080",
            "0",
            5
        )

    private val cantusService: CantusService = mockk()

    @BeforeEach
    fun setupMock() {
        every {
            cantusService.getImageMetadata(
                "fluent", "fluent-bit", "0"
            )
        } returns
            ImageMetadata(
                "docker.registry/fluent/fluent-bit",
                "0",
                "sha:1234"
            )
    }

    @AfterEach
    fun clearMocks() {
        HttpMock.clearAllHttpMocks()
    }

    @Test
    fun `should add fluentbit sidecar to dc`() {
        val (dcResource, parserResource, configResource, secretResource) = generateResources(
            """{
             "logging" : {
                "index": "test-index",
                "loggers": {
                    "sensitive" : "sensitive-index",
                    "slow" : false,
                    "gc" : "false",
                    "audit_json" : "aud-index",
                    "audit_text" : "aud-index"
                }
             } 
           }""",
            createEmptyDeploymentConfig(), emptyList(), 3
        )
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added fluentbit volume, sidecar container and annotation")
            .auroraResourceMatchesFile("dc.json")

        assertThat(parserResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("parser.json")

        assertThat(configResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("config.json")

        assertThat(secretResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("secret.json")
    }

    @Test
    fun `should add splunk connect annotations for templates and no sidecar`() {
        val (dcResource) = generateResources(
            """{
             "type" : "template",
             "logging": {
                "index": "test-index"
             }
           }""",
            createEmptyDeploymentConfig(), emptyList(), 0
        )
        assertThat(dcResource).isNotNull()
        val config = dcResource.resource as DeploymentConfig
        val annotations = config.spec.template.metadata.annotations
        assertThat(annotations).isNotNull()
        assertThat(annotations["splunk.com/index"]).isEqualTo("test-index")
    }

    @Test
    fun `should add splunk connect annotations for cronjob and no sidecar`() {
        val (dcResource) = generateResources(
            """{
             "type" : "cronjob",
             "logging": {
                "index": "test-index"
             }
           }""",
            createEmptyDeploymentConfig(), emptyList(), 0
        )
        assertThat(dcResource).isNotNull()
        val config = dcResource.resource as DeploymentConfig
        val annotations = config.spec.template.metadata.annotations
        assertThat(annotations).isNotNull()
        assertThat(annotations["splunk.com/index"]).isEqualTo("test-index")
    }

    @Test
    fun `should not add annotations or sidecar when index is not set for template`() {
        val (dcResource) = generateResources(
            """{
             "type" : "template",
             "logging": {
                "index": ""
             }
           }""",
            createEmptyDeploymentConfig(), emptyList(), 0
        )
        assertThat(dcResource).isNotNull()
        val config = dcResource.resource as DeploymentConfig
        assertThat(config.spec.template.metadata).isNull()
    }

    @Test
    fun `setting buffer size should be reflected in deploymentconfig and fluentbit config`() {
        val (dcResource, parserResource, configResource, secretResource) = generateResources(
            """{
             "logging" : {
                "index": "test-index",
                "bufferSize": "10"
             } 
           }""",
            createEmptyDeploymentConfig(), emptyList(), 3
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added fluentbit volume, sidecar container and annotation")
            .auroraResourceMatchesFile("dc_resized.json")

        assertThat(configResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("config_resized.json")
    }

    @Test
    fun `Should validate required fields for custom logging`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
             "logging" : {
                "custom": {
                    "otherName": {
                        "index": "hello",
                        "sourcetype": "log4j",
                        "pattern": "application.log"
                    }
                }
             } 
           }"""
            )
        }.singleApplicationErrorResult("When using custom logger, application logger is required")
    }

    @Test
    fun `Should validate only one of custom or standard loggers`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
             "logging" : {
                "loggers": {
                    "gc": "gc_log"
                },
                "custom": { "otherName": {
                        "index": "hello",
                        "sourcetype": "log4j",
                        "pattern": "application.log"
                    }
                }
             } 
           }"""
            )
        }.singleApplicationErrorResult("Cannot use both custom loggers and the default loggers")
    }

    @Test
    fun `Should validate only one of custom or default index`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
             "logging" : {
                "index": "openshift-test",
                "custom": {
                    "otherName": {
                        "index": "hello",
                        "sourcetype": "log4j",
                        "pattern": "application.log"
                    }
                }
             } 
           }"""
            )
        }.singleApplicationErrorResult("Cannot use both custom loggers and the default loggers")
    }

    @Test
    fun `Should validate required fields for custom logger`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
             "logging" : {
                "custom": {
                    "otherName": {
                        "sourcetype": "log4j",
                        "pattern": "application.log"
                    }
                }
             } 
           }"""
            )
        }.singleApplicationErrorResult("Field is required")

        assertThat {
            createAuroraDeploymentContext(
                """{
             "logging" : {
                "custom": {
                    "otherName": {
                        "index": "openshift",
                        "pattern": "application.log"
                    }
                }
             } 
           }"""
            )
        }.singleApplicationErrorResult("Field is required")

        assertThat {
            createAuroraDeploymentContext(
                """{
             "logging" : {
                "custom": {
                    "otherName": {
                        "index": "openshift",
                        "sourcetype": "fluentbit"
                    }
                }
             } 
           }"""
            )
        }.singleApplicationErrorResult("Field is required")
    }

    @Test
    fun `Should validate that sourcetype is within supported sourcetypes`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
             "logging" : {
                "custom": {
                    "otherName": {
                        "index": "openshift",
                        "sourcetype": "unsupported",
                        "pattern": "application.log"
                    }
                }
             } 
           }"""
            )
        }.singleApplicationErrorResult("Must be one of [_json, access_combined")
    }

    @ValueSource(strings = ["application", "application.log.other", "application231243.log", ".", "", "da.", ".log"])
    @ParameterizedTest
    fun `Should validate that pattern is within regex`(illegalPattern: String) {
        assertThat {
            createAuroraDeploymentContext(
                """{
             "logging" : {
                "custom": {
                    "application": {
                        "index": "openshift",
                        "sourcetype": "log4j",
                        "pattern": "$illegalPattern"
                    }
                }
             } 
           }"""
            )
        }.singleApplicationErrorResult("Is not properly formatted. You need to have exactly one period")
    }

    @Test
    fun `Should not create fluentbit when index is empty`() {
        val (dcResource) = generateResources(
            """{
             "logging" : {
                "index": "",
                "loggers": {
                    "gc": "gc_log"
                }
             } 
           }""",
            createEmptyDeploymentConfig(), emptyList(), 0
        )
        val dc = dcResource.resource as DeploymentConfig

        assertThat(dc.spec.template.spec.containers.size).isEqualTo(1)
    }

    @Test
    fun `Should not create fluentbit when index is not configured`() {
        assertThat {
            createAuroraDeploymentContext(
                """{
             "logging" : {
                "loggers": {
                    "gc": "gc_log"
                }
             } 
           }""",
            )
        }.singleApplicationErrorResult("Missing required field logging/index")
    }

    @Test
    fun `Should be able to create fluentbit sidecar with custom loggers`() {
        val (dcResource, parserResource, configResource, secretResource) = generateResources(
            """{
             "logging" : {
                "custom": {
                    "application": {
                        "index": "openshift-test",
                        "pattern": "application.log",
                        "sourcetype": "log4j"
                    },
                    "access": {
                        "index": "openshift-other",
                        "pattern": "access.log",
                        "sourcetype": "access_combined"
                    }
                }
             } 
           }""",
            createEmptyDeploymentConfig(), emptyList(), 3
        )
        val dc = dcResource.resource as DeploymentConfig

        assertThat(dc.spec.template.spec.containers.size).isEqualTo(2)

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added fluentbit volume, sidecar container and annotation")
            .auroraResourceMatchesFile("dc_custom.json")

        assertThat(configResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("config_custom.json")
    }
}
