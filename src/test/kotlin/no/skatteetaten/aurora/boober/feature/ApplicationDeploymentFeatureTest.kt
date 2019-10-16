package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fkorotkov.kubernetes.newOwnerReference
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class ApplicationDeploymentFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = ApplicationDeploymentFeature()

    @Test
    fun `geneate application deployment`() {

        // Right now this method only takes the app file and a single "empty" resource
        // how should we model this to allow adding more AuroraConfigFiles and more empty resources
        val resources = generateResources(
            """{ "message" : "This is a note", 
                "ttl" : "1d" }""", createEmptyDeploymentConfig()
        )

        assertThat(resources.size).isEqualTo(2)
        val ad = resources.last()
        val dc = resources.first()
        assertThat(ad).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("ad.json")

        assertThat(dc).auroraResourceModifiedByThisFeatureWithComment("Set owner reference to ApplicationDeployment")
        assertThat(dc.resource.metadata.ownerReferences[0]).isEqualTo(newOwnerReference {
            apiVersion = "skatteetaten.no/v1"
            kind = "ApplicationDeployment"
            name = ad.resource.metadata.name
            uid = "123-123"
        })
    }

    // TODO:Is this a good error message?
    @Test
    fun `get error if ttl duration string is wrong`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ "ttl" : "asd"  }"""
            )
        }.singleApplicationError("'asd' is not a valid simple duration")
    }
}
