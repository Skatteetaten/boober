package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import assertk.assertThat
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError

private val logger = KotlinLogging.logger { }

class DatabaseFeatureDisabledTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = DatabaseDisabledFeature("utv")

    @Test
    fun `get error if trying to create database`() {

        assertThat {
            generateResources(
                """{ 
               "database" : true
           }"""
            )
        }.singleApplicationError("Databases are not supported in this cluster")
    }
}
