package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isSuccess
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

private val logger = KotlinLogging.logger { }

class S3FeatureDisabledTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = S3DisabledFeature("u")

    @Test
    fun `get error if trying to create s3`() {
        assertThat { generateResources(
            """{ 
               "s3" : true
           }"""
        ) }.singleApplicationError("S3 storage is not available in this cluster=utv")
    }

    @Test
    fun `get  if trying to create s3`() {
        assertThat { generateResources(
            """{
           }"""
        ) }.isSuccess()
    }
}
