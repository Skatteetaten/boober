package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import com.fasterxml.jackson.module.kotlin.convertValue
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.openshift.api.model.ImageStream
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.Companion.adr
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService
import no.skatteetaten.aurora.boober.utils.AbstractOpenShiftObjectGeneratorTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OpenShiftObjectGeneratorImageStreamTest : AbstractOpenShiftObjectGeneratorTest() {

    lateinit var objectGenerator: OpenShiftObjectGenerator

    @BeforeEach
    fun setupTest() {
        objectGenerator = createObjectGenerator()
    }

    @Test
    fun `Verify labels are created properly`() {

        val auroraConfigJson = mapOf(
            "about.json" to DEFAULT_ABOUT,
            "utv/about.json" to DEFAULT_UTV_ABOUT,
            "aos-simple.json" to AOS_SIMPLE_JSON,
            "utv/aos-simple.json" to """{ "version": "SNAPSHOT-feature_MFU_3056-20171122.091423-23-b2.2.5-oracle8-1.4.0" }"""
        )

        val deploymentSpec = createDeploymentSpec(auroraConfigJson = auroraConfigJson, ref = adr("utv", "aos-simple"))

        val imageStream = objectGenerator.generateImageStream(
            deployId = "deploy-id",
            auroraDeploymentSpecInternal = deploymentSpec,
            reference = OwnerReference()
        )!!

        val imageStreamObject: ImageStream = mapper.convertValue(imageStream)

        val labels = imageStreamObject.metadata.labels

        assertThat(labels["affiliation"]).isEqualTo(AFFILIATION)
        assertThat(labels["app"]).isEqualTo("aos-simple")
        assertThat(labels["booberDeployId"]).isEqualTo("deploy-id")
        assertThat(labels["releasedVersion"]).isEqualTo("APSHOT-feature_MFU_3056-20171122.091423-23-b2.2.5-oracle8-1.4.0")
        assertThat(labels["updatedBy"]).isEqualTo("aurora")

        labels.forEach {
            assertThat(it.value.length).isLessThanOrEqualTo(OpenShiftObjectLabelService.MAX_LABEL_VALUE_LENGTH)
        }
    }
}
