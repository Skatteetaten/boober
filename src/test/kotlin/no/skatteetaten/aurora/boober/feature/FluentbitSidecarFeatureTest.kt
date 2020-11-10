package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.fail
import no.skatteetaten.aurora.boober.model.AuroraConfigException
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class FluentbitSidecarFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = FluentbitSidecarFeature("test_hec", "splunk.url", "8080")

    @Test
    fun `Custom configuration should fail validation when missing elements`() {
        try {
        val (dcResource, parserResource, configResource, secretResource) = generateResources(
            """{
             "logging" : {
                "index": "test-index",
                "custom" : {
                    "test" : {
                        "index": "custom-index"
                    }
                }
             } 
           }""",
            createEmptyDeploymentConfig(), emptyList(), 3
        )
        fail("Expected validation error, none thrown")
        } catch (e: MultiApplicationValidationException) {
            assertThat(e.errors.size).isEqualTo(1)
            assertThat((e.errors.get(0).errors.get(0) as AuroraConfigException).errors.size).isEqualTo(2)
        }
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
                },
                "custom" : {
                    "test" : {
                        "index": "custom-index",
                        "pattern": "*.custom",
                        "sourcetype": "custom"
                    }
                }
             } 
           }""",
                createEmptyDeploymentConfig(), emptyList(), 3
        )
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added fluentbit volume and sidecar container")
            .auroraResourceMatchesFile("dc.json")

        assertThat(parserResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("parser.json")

        assertThat(configResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("config.json")

        assertThat(secretResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("secret.json")
    }
}
