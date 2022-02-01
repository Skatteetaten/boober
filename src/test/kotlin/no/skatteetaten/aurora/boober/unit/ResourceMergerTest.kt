package no.skatteetaten.aurora.boober.unit

import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.service.openshift.mergeWithExistingResource
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ResourceMergerTest : ResourceLoader() {

    enum class OpenShiftResourceTypeTestData(
        val fields: List<String>
    ) {

        SERVICE(listOf("/metadata/resourceVersion", "/spec/clusterIP")),
        DEPLOYMENTCONFIG(listOf("/metadata/resourceVersion", "/spec/template/spec/containers/0/image")),
        PVC(listOf("/spec/volumeName")),
        BUILDCONFIG(
            listOf(
                "/metadata/resourceVersion",
                "/spec/triggers/0/imageChange/lastTriggeredImageID",
                "/spec/triggers/1/imageChange/lastTriggeredImageID"
            )
        ),
        CONFIGMAP(listOf("/metadata/resourceVersion")),
        NAMESPACE(listOf("/metadata/annotations")),
        AURORACNAME(listOf("/metadata/annotations")),
        AURORAAZURECNAME(listOf("/metadata/annotations")),
    }

    @ParameterizedTest
    @EnumSource(OpenShiftResourceTypeTestData::class)
    fun `Should update`(test: OpenShiftResourceTypeTestData) {

        val type = test.name.lowercase()
        val oldResource = loadJsonResource("$type.json")
        val newResource = loadJsonResource("$type-new.json")
        val merged = mergeWithExistingResource(newResource, oldResource)
        test.fields.forEach {
            assertThat(merged.at(it)).isEqualTo(oldResource.at(it))
        }
    }

    @ParameterizedTest
    @EnumSource(OpenShiftResourceTypeTestData::class)
    fun `Should accept that element to retain might be missing for `(test: OpenShiftResourceTypeTestData) {
        val type = test.name.lowercase()
        val oldResource = loadJsonResource("$type.json")
        val newResource = loadJsonResource("$type-new.json")
        val merged = mergeWithExistingResource(newResource, oldResource)
        test.fields.forEach {
            assertThat(merged.at(it)).isEqualTo(oldResource.at(it))
        }
    }

    @Test
    fun `Should accept changes to sidecar container images`() {
        val oldDc = loadJsonResource("deploymentconfig.json")
        val newDc = loadJsonResource("deploymentconfig-new.json")
        val merged = mergeWithExistingResource(newDc, oldDc)
        val sidecarImageField = "/spec/template/spec/containers/1/image"

        assertThat(merged.at(sidecarImageField)).isEqualTo(newDc.at(sidecarImageField))
    }

    @Test
    fun `Should load image information from the correct, existing application container`() {
        val oldDc = loadJsonResource("deploymentconfig-prependedsidecar.json")
        val newDc = loadJsonResource("deploymentconfig-new.json")
        val merged = mergeWithExistingResource(newDc, oldDc)
        val containersField = "/spec/template/spec/containers"
        val oldApplicationImageField = "$containersField/1/image"
        val newApplicationImageField = "$containersField/0/image"
        val newAppendedSidecarImageField = "$containersField/1/image"

        assertThat(merged.at(newApplicationImageField)).isEqualTo(oldDc.at(oldApplicationImageField))
        assertThat(merged.at(newAppendedSidecarImageField)).isEqualTo(newDc.at(newAppendedSidecarImageField))
        assertThat(merged.at(containersField).size()).isEqualTo(newDc.at(containersField).size())
    }
}
