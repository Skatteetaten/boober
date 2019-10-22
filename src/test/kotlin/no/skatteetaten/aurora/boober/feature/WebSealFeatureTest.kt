package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class WebSealFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = WebsealFeature()

    @Test
    fun `should annotate service with webseal labels`() {

        val resources = generateResources("""{
             "webseal" : true 
           }""", createEmptyService())

        assertThat(resources.size).isEqualTo(1)
        val resource = resources.first()
        assertThat(resource).auroraResourceModifiedByThisFeatureWithComment("Set webseal annotations")
        assertThat(resource.resource.metadata.annotations["sprocket.sits.no/service.webseal"]).isEqualTo("simple-paas-utv")
    }

    @Test
    fun `should annotate service with webseal labels and roles`() {

        val resources = generateResources("""{
             "webseal" : {
               "host" : "simple2-paas-utv",
               "roles" : "foo,bar,baz"
             }
           }""", createEmptyService())

        assertThat(resources.size).isEqualTo(1)
        val resource = resources.first()
        assertThat(resource).auroraResourceModifiedByThisFeatureWithComment("Set webseal annotations")
        assertThat(resource.resource.metadata.annotations["sprocket.sits.no/service.webseal"]).isEqualTo("simple2-paas-utv")
        assertThat(resource.resource.metadata.annotations["sprocket.sits.no/service.webseal-roles"]).isEqualTo("foo,bar,baz")
    }
}
