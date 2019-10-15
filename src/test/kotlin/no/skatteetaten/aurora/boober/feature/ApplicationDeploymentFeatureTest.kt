package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fkorotkov.kubernetes.newOwnerReference
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

// TODO: How should we assert on the created resources here? Should we have them on the file system or what?
class ApplicationDeploymentFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = ApplicationDeploymentFeature()

    @Test
    fun `geneate application deployment`() {

        val resources = generateResources(
            """{ 
                "message" : "This is a note", 
                "ttl" : "1d"
        }""".trimIndent(), existingResources = mutableSetOf(createDCAuroraResource())
        )

        // TODO: assert the content of the ad resource. From file or what?

        val ad = resources.last()
        val dc = resources.first()
        assertThat(resources.size).isEqualTo(2)
        assertThat(ad).createdByThisFeature()
        assertThat(dc).modifiedWithComment("Set owner reference to ApplicationDeployment")
        assertThat(dc.resource.metadata.ownerReferences[0]).isEqualTo(newOwnerReference {
            apiVersion = "skatteetaten.no/v1"
            kind = "ApplicationDeployment"
            name = ad.resource.metadata.name
            uid = "123-123"
        })
    }

    @Test
    fun `get error if ttl duration string is wrong`() {
    }
}

