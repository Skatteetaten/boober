package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import assertk.assertThat
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.CantusService
import no.skatteetaten.aurora.boober.service.ImageMetadata
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock

class JobFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = JobFeature("docker.registry", cantusService)

    private val cantusService: CantusService = mockk()

    @BeforeEach
    fun setupMock() {
        every {
            cantusService.getImageMetadata(
                "aurora", "turbo", "0"
            )
        } returns
            ImageMetadata(
                "docker.registry/aurora/turbo",
                "0",
                "sha:1234"
            )
    }

    @AfterEach
    fun clearMocks() {
        HttpMock.clearAllHttpMocks()
    }

    @Test
    fun `should not allow cronjob without schedule`() {

        assertThat {
            createAuroraDeploymentContext(
                """{
                "type" : "cronjob"
        }"""
            )
        }.singleApplicationError(
            "Cron schedule is required."
        )
    }

    @Test
    fun `should not allow job with non numeric successCount`() {

        assertThat {
            createAuroraDeploymentContext(
                """{
                "type" : "cronjob",
                "schedule" : "0/5 * * * *",
                "successCount" : "foobar"
        }"""
            )
        }.singleApplicationError(
            "Not a valid int value."
        )
    }

    @Test
    fun `should not allow job with invalid schedule`() {

        assertThat {
            createAuroraDeploymentContext(
                """{
                "type" : "cronjob",
                "schedule" : "0/5 * * *"
        }"""
            )
        }.singleApplicationError(
            "Cron expression contains 4 parts but we expect one of [5]."
        )
    }

    @Test
    fun `should not allow job with wrong concurrency policy`() {

        assertThat {
            createAuroraDeploymentContext(
                """{
                "type" : "cronjob",
                "schedule" : "0/5 * * * *",
                "concurrentPolicy" : "Foobar"
        }"""
            )
        }.singleApplicationError(
            "Must be one of [Allow, Replace, Forbid]"
        )
    }

    @Test
    fun `should generate job`() {

        val (jobResource) = generateResources(
            """{
                "type" : "job",
                "artifactId" : "turbo", 
                "groupId": "aurora",
                "version": "0"
        }"""
        )

        assertThat(jobResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("job.json")
    }

    @Test
    fun `should generate cronjob`() {

        val (jobResource) = generateResources(
            """{
                "type" : "cronjob",
                "schedule" : "0/5 * * * *",
                "artifactId" : "turbo", 
                "groupId": "aurora",
                "version": "0"
        }"""
        )

        assertThat(jobResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("cronjob.json")
    }
}
