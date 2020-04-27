package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

class WebSealFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = WebsealFeature(".test.skead.no")

    @Test
    fun `should not allow misspelled false for boolean webseal|strict flag`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                    "webseal" : {
                      "strict" : "fasle"
                     }
               }"""
            )
        }.singleApplicationError("Not a valid boolean value.")
    }

    @Test
    fun `should create webseal route`() {

        val (route) = modifyResources(
            """{
             "webseal" : true 
           }""")

        assertThat(route).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route.json")
    }

    @Test
    fun `should create webseal route with roles and custom host`() {

        val (route) = modifyResources(
            """{
             "webseal" : {
               "host" : "simple2-paas-utv",
               "roles" : "foo,bar,baz"
             }
           }""")

        assertThat(route).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route-custom.json")
    }
}
