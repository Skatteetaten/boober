package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import no.skatteetaten.aurora.boober.utils.singleApplicationValidationError
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
    fun `Required fields for custom logging`() {
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
        }.singleApplicationValidationError("Required something here")

    }
}
