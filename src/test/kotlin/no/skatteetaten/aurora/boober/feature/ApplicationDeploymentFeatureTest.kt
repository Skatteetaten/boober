package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import com.fkorotkov.kubernetes.newOwnerReference
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSuccess
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError

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
                    "mattermost":{
                      "channel1": true,
                      "channel2": {
                        "enabled": true
                      },
                      "channel3":{
                        "enabled": false
                      },
                      "channel4": false
                    },
                    "email" : {
                      "foo@bar.no": {
                         "enabled": true
                      },
                      "bar@foo.no": true,
                      "baz@skatt.no":{
                        "enabled": false
                      },
                      "bat@skatt.no": false
                    }
                }
                }""",
            createEmptyDeploymentConfig()
        )

        assertThat(ad).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("ad.json")

        assertThat(dc).auroraResourceModifiedByFeatureWithComment(
            feature = ApplicationDeploymentFeature::class.java,
            comment = "Added env vars"
        )
        assertThat(dc).auroraResourceModifiedByFeatureWithComment(
            feature = ApplicationDeploymentFeature::class.java,
            comment = "Set owner reference to ApplicationDeployment"
        )
        assertThat(dc.resource.metadata.ownerReferences[0]).isEqualTo(
            newOwnerReference {
                apiVersion = "skatteetaten.no/v1"
                kind = "ApplicationDeployment"
                name = ad.resource.metadata.name
                uid = "123-123"
            }
        )
    }

    @Test
    fun `get error with wrong email`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """ {
                  "notification" : { 
                    "email": {
                      "asd": {
                        "enabled": true
                      }
                    }
                  }
                }"""
            )
        }.singleApplicationError("""Email address 'asd' is not a valid email address.""")
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
            assertThat(it.first.first().spec.applicationDeploymentId).isEqualTo("fallbackid")
        }
    }
}
