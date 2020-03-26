package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

class JobFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = JobFeature("docker.registry")

    @Test
    fun `should not allow cronjob without scheduel`() {

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
    fun `should not allow job with script and command`() {

        assertThat {
            createAuroraDeploymentContext(
                """{
                "type" : "job",
                "command" : "curl",
                "arguments" : ["-vvv", "http://foo"],
                "script" : "curl -vvv http://foo"
        }"""
            )
        }.singleApplicationError(
            "Job script and command/arguments are not compatible. Choose either script or command/arguments"
        )
    }

    @Test
    fun `should generate job`() {

        val (jobResource) = generateResources(
            """{
                "type" : "job",
                "command" : "curl",
                "arguments" : ["-vvv", "http://foo"]
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
                "command" : "curl",
                "arguments" : ["-vvv", "http://foo"]
        }"""
        )

        assertThat(jobResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("cronjob.json")
    }

    @Test
    fun `should generate cronjob with script`() {

        val (jobResource, configMapResource) = generateResources(
            """{
                "type" : "cronjob",
                "script" : "curl -vvv http://localhost",
                "schedule" : "0/5 * * * *"
        }"""
        )

        assertThat(jobResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("job-with-script.json")
        assertThat(configMapResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("configmap-script.json")
    }
}
