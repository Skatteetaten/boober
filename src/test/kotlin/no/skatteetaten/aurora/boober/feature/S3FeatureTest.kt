package no.skatteetaten.aurora.boober.feature

import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class S3FeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = S3Feature()

    @Test
    fun a() {
        val resources = generateResources(
            """{ 
               "s3" : true
           }""", createdResources = 1, resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        resources.forEach {
            println(it)
        }

        val dc = resources.find { it.resource is DeploymentConfig }
            ?.let { it.resource as DeploymentConfig }
            ?: throw Exception("No dc")

        println(dc.spec.template.spec.containers)
    }
}
