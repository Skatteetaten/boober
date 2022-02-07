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
        PROJECT(listOf("/metadata/annotations")),
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

    @Test
    fun `Should accept changes to sidecar container images`() {
        val oldDc = loadJsonResource("deploymentconfig.json")
        val newDc = loadJsonResource("deploymentconfig-new.json")
        val merged = mergeWithExistingResource(newDc, oldDc)
        val sidecarImageField = "/spec/template/spec/containers/1/image"

        assertThat(merged.at(sidecarImageField)).isEqualTo(newDc.at(sidecarImageField))
    }

    @Test
    fun `Should preserve project labels`() {
        val oldProject = loadJsonResource("project.json")
        val newProject = loadJsonResource("project-new.json")
        val merged = mergeWithExistingResource(newProject, oldProject)
        val labelsField = "/metadata/labels"

        assertThat(merged.at(labelsField)["network.openshift.io/policy-group"]).isEqualTo(oldProject.at(labelsField)["network.openshift.io/policy-group"])
    }
}
