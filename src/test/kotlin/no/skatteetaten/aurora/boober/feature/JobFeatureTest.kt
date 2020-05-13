package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

class JobFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = JobFeature("docker.registry")

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
