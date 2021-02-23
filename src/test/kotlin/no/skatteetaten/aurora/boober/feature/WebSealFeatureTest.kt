package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import assertk.assertThat
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError

class WebSealFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = WebsealFeature(".test.skead.no")

    @Test
    fun `should not allow timeout with invalid string`() {
        assertThat {
            createAuroraConfigFieldHandlers(
                """{ 
                    "webseal" : {
                      "clusterTimeout" : "asd"
                     }
               }"""
            )
        }.singleApplicationError("'asd' is not a valid simple duration")
    }

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
           }"""
        )

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
           }"""
        )

        assertThat(route).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route-custom.json")
    }

    @Test
    fun `should create webseal route with roles and custom host and timeout`() {

        val (route) = modifyResources(
            """{
             "webseal" : {
               "host" : "simple2-paas-utv",
               "roles" : "foo,bar,baz",
               "clusterTimeout" : "10s"
             }
           }"""
        )

        assertThat(route).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route-timeout.json")
    }

    @ParameterizedTest
    @ValueSource(strings = ["10", "10s"])
    fun `should create webseal route with roles and custom host and timeout`(timeout: String) {

        val (route) = modifyResources(
            """{
             "webseal" : {
               "host" : "simple2-paas-utv",
               "roles" : "foo,bar,baz",
               "clusterTimeout" : "$timeout"
             }
           }"""
        )

        assertThat(route).auroraResourceCreatedByThisFeature()
            .auroraResourceMatchesFile("route-timeout.json")
    }
}
