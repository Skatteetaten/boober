package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.Test

class JobFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = JobFeature("docker.registry")

    @Test
    fun `should not allow job with script and command`() {

        assertThat {
            createAuroraDeploymentContext(
                """{
                "type" : "job",
                "groupId" : "foo.bar",
                "version" : "1",
                "job" : {
                  "schedule" : "0/5 * * * ?",
                  "command" : "curl",
                  "arguments" : ["-vvv", "http://foo"],
                  "script" : "curl -vvv http://foo"
                }
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
                "groupId" : "foo.bar",
                "version" : "1",
                "job" : {
                  "command" : "curl",
                  "arguments" : ["-vvv", "http://foo"]
                }
        }"""
        )

        assertThat(jobResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("job.json")
    }

    @Test
    fun `should generate cronjob`() {

        val (jobResource) = generateResources(
            """{
                "type" : "job",
                "groupId" : "foo.bar",
                "version" : "1",
                "job" : {
                  "schedule" : "0/5 * * * ?",
                  "command" : "curl",
                  "arguments" : ["-vvv", "http://foo"]
                }
        }"""
        )

        assertThat(jobResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("cronjob.json")
    }

    @Test
    fun `should generate cronjob with script`() {

        val (jobResource, configMapResource) = generateResources(
            """{
                "type" : "job",
                "groupId" : "foo.bar",
                "version" : "1",
                "job" : {
                  "script" : "curl -vvv http://localhost",
                  "schedule" : "0/5 * * * ?"
                }
        }"""
        )

        assertThat(jobResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("job-with-script.json")
        assertThat(configMapResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("configmap-script.json")
    }
}
