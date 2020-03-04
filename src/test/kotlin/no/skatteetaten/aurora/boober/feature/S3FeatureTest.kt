package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.containsAll
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningResult
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.findResourceByType
import org.junit.jupiter.api.Test

class S3FeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = S3Feature(s3Provisioner)

    private val s3Provisioner: S3Provisioner = mockk()

    @Test
    fun `verify creates secret with value mappings in dc`() {

        val request = S3ProvisioningRequest(affiliation, environment, appName)
        every { s3Provisioner.provision(request) } returns S3ProvisioningResult(
            request,
            "http://locahost:9000",
            "accessKey",
            "secretKey",
            "default",
            appName,
            "us-east-1"
        )

        val resources = generateResources(
            """{ 
               "beta" : {
                   "s3": true
               }
           }""",
            createdResources = 1,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        val secret: Secret = resources.findResourceByType()

        assertThat(secret.data.keys).containsAll("serviceEndpoint", "accessKey", "secretKey", "bucketRegion", "bucketName", "objectPrefix")

        val dc = resources.find { it.resource is DeploymentConfig }?.let { it.resource as DeploymentConfig }
            ?: throw Exception("No dc")

        val container = dc.spec.template.spec.containers.first()
        val actualEnvs = container.env.map { it.name to it.valueFrom.secretKeyRef.let { "${it.name}/${it.key}" } }
        val secretName = "$appName-s3"
        val expectedEnvs = listOf(
            "S3_SERVICEENDPOINT" to "$secretName/serviceEndpoint",
            "S3_ACCESSKEY" to "$secretName/accessKey",
            "S3_SECRETKEY" to "$secretName/secretKey",
            "S3_BUCKETREGION" to "$secretName/bucketRegion",
            "S3_BUCKETNAME" to "$secretName/bucketName",
            "S3_OBJECTPREFIX" to "$secretName/objectPrefix"
        )
        assertThat(actualEnvs).containsAll(*expectedEnvs.toTypedArray())
    }
}
