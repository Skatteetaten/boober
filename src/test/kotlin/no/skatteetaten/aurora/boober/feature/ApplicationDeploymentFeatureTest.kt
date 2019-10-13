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
            """
            "message" : "This is a note", 
            "ttl" : "1d"
        """.trimIndent(), existingResources = mutableSetOf(createDCAuroraResource())
        )

        assertThat(resources.size).isEqualTo(2)

        val ad = resources.last()
        assertThat(ad.createdSource.feature).isEqualTo(ApplicationDeploymentFeature::class.java)

        val dc = resources.first()
        assertThat(dc.sources.first().feature).isEqualTo(ApplicationDeploymentFeature::class.java)
        assertThat(dc.sources.first().comment).isEqualTo("Set owner refrence to ApplicationDeployment")
        assertThat(dc.resource.metadata.ownerReferences[0]).isEqualTo(newOwnerReference {
            apiVersion = "skatteetaten.no/v1"
            kind = "ApplicationDeployment"
            name = ad.resource.metadata.name
            uid = "123-123"
        })
    }
}