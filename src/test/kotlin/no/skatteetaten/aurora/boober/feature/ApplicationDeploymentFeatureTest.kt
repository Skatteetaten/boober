package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSuccess
import com.fkorotkov.kubernetes.newOwnerReference
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

class ApplicationDeploymentFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = ApplicationDeploymentFeature()

    @Test
    fun `geneate application deployment`() {

        val (dc, ad) = generateResources(
            """{ 
            "message" : "This is a note", 
                "ttl" : "1d",
                "notification" : {
                    "email" : "foo@bar.no"
                }
                }""", createEmptyDeploymentConfig()
        )

        assertThat(ad).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("ad.json")

        assertThat(dc).auroraResourceModifiedByThisFeatureWithComment(comment = "Added env vars", index = 0)
        assertThat(dc).auroraResourceModifiedByThisFeatureWithComment(comment = "Set owner reference to ApplicationDeployment", index = 1)
        assertThat(dc.resource.metadata.ownerReferences[0]).isEqualTo(newOwnerReference {
            apiVersion = "skatteetaten.no/v1"
            kind = "ApplicationDeployment"
            name = ad.resource.metadata.name
            uid = "123-123"
        })
    }

    @Test
    fun `get error with wrong email`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ "notification" : { "email": "asd"  }}"""
            )
        }.singleApplicationError("""Email address 'asd' is not a valid email address according to [a-zA-Z0-9\+\.\_\%\-\+]{1,256}\@[a-zA-Z0-9][a-zA-Z0-9\-]{0,64}(\.[a-zA-Z0-9][a-zA-Z0-9\-]{0,25})+""")
    }
    @Test
    fun `get error if ttl duration string is wrong`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ "ttl" : "asd"  }"""
            )
        }.singleApplicationError("'asd' is not a valid simple duration")
    }

    @Test
    fun `Should get id when using idServiceFallback`() {
        assertThat {
            createAuroraDeploymentContext(useHerkimerIdService = false)
        }.isSuccess().given {
            assertThat(it.spec.applicationDeploymentId).isEqualTo("fallbackid")
        }
    }
}
