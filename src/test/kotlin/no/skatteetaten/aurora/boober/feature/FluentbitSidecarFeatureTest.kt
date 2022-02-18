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
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock

class FluentbitSidecarFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = FluentbitSidecarFeature(
            cantusService,
            FluentBitConfigurator(),
            "test_hec",
            "splunk.url",
            "8080",
            "0",
            "300m",
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
    fun `should add fluentbit to dc`() {
        // mockVault("foo")
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
    fun `should validate but not generate sidecar setup for templates`() {
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
    fun `should validate but not generate sidecar setup for cronjob`() {
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
    fun `should validate and not generate sidecar setup for templates with empty index`() {
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
    fun `setting buffer size should be reflected in deployment config and fluent bit config`() {
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
}
