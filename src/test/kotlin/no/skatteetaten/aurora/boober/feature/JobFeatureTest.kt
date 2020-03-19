package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class JobFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = JobFeature("docker.registry")

    @Test
    fun `should generate job`() {

        val (jobResource) = generateResources(
            """{
                "type" : "job",
                "groupId" : "foo.bar",
                "version" : "1",
                "job" : {
                  "schedule" : "0/5 * * * ?"
                }
        }"""
        )

        assertThat(jobResource).auroraResourceCreatedByThisFeature().auroraResourceMatchesFile("job.json")
    }
}
