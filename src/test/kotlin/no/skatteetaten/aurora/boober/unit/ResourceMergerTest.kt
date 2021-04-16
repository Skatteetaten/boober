package no.skatteetaten.aurora.boober.unit

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
    }

    @ParameterizedTest
    @EnumSource(OpenShiftResourceTypeTestData::class)
    fun `Should update`(test: OpenShiftResourceTypeTestData) {

        val type = test.name.toLowerCase()
        val oldResource = loadJsonResource("$type.json")
        val newResource = loadJsonResource("$type-new.json")
        val merged = mergeWithExistingResource(newResource, oldResource)
        test.fields.forEach {
            assertThat(merged.at(it)).isEqualTo(oldResource.at(it))
        }
    }
}
