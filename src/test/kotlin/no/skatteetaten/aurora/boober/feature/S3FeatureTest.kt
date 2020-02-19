package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.containsAll
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test

class S3FeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = S3Feature(S3Provisioner())

    @Test
    fun `verify creates secret with value mappings in dc`() {
        val resources = generateResources(
            """{ 
               "beta" : {
                   "s3": true
               }
           }""",
            createdResources = 1,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        val secret = resources.find { it.resource is Secret }?.let { it.resource as Secret }
            ?: throw Exception("No Secret created")

        assertThat(secret.data.keys).containsAll("serviceEndpoint", "accessKey", "secretKey", "objectPrefix")

        val dc = resources.find { it.resource is DeploymentConfig }?.let { it.resource as DeploymentConfig }
            ?: throw Exception("No dc")

        val container = dc.spec.template.spec.containers.first()
        val actualEnvs = container.env.map { it.name to it.valueFrom.secretKeyRef.let { "${it.name}/${it.key}" } }
        val secretName = "$appName-s3"
        val expectedEnvs = listOf(
            "S3_SERVICEENDPOINT" to "$secretName/serviceEndpoint",
            "S3_ACCESSKEY" to "$secretName/accessKey",
            "S3_SECRETKEY" to "$secretName/secretKey",
            "S3_BUCKETNAME" to "$secretName/bucketName",
            "S3_OBJECTPREFIX" to "$secretName/objectPrefix"
        )
        assertThat(actualEnvs).containsAll(*expectedEnvs.toTypedArray())
    }
}
