package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.isEqualTo
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ObjectAreaWithCredentials
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ObjectArea
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3StorageGridProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StorageGridCredentials
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class S3StorageGridFeatureTest : AbstractFeatureTest() {
    val s3StorageGridProvisioner = mockk<S3StorageGridProvisioner>()

    override val feature: Feature
        get() {
            return S3StorageGridFeature(
                s3StorageGridProvisioner,
                mockk(),
            )
        }

    @AfterEach
    fun after() {
        HttpMock.clearAllHttpMocks()
    }

    val tenant = "paas-utv"
    val bucketName = "theBucket"

    @Test
    fun `creates secretes and environment variable refs for provisioned credentials`() {

        val area1Name = "default"
        val area2Name = "min-andre-bucket"

        every { s3StorageGridProvisioner.getOrProvisionCredentials(any()) } returns listOf(
            ObjectAreaWithCredentials(
                S3ObjectArea(tenant, bucketName, area1Name),
                StorageGridCredentials(
                    tenant,
                    "endpoint",
                    "accessKey",
                    "secretKey",
                    bucketName,
                    "objectPrefix",
                    "username",
                    "password"
                )
            ),
            ObjectAreaWithCredentials(
                S3ObjectArea(tenant, bucketName, area2Name),
                StorageGridCredentials(
                    tenant,
                    "endpoint",
                    "accessKey",
                    "secretKey",
                    bucketName,
                    "objectPrefix",
                    "username",
                    "password"
                )
            )
        )

        val resources = generateResources(
            """{ 
                "s3Defaults": {
                    "bucketName": "theBucket",
                    "tenant": "paas-utv"
                },
                "s3": {
                    "$area1Name": {
                        "enabled": true
                    },
                    "$area2Name": {
                        "enabled": true
                    }
                }
           }""",
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig()),
            createdResources = 2
        )

        resources.verifyS3SecretsAndEnvs(listOf(area1Name, area2Name))
    }

    private fun List<AuroraResource>.verifyS3SecretsAndEnvs(expectedObjectAreas: List<String> = listOf("default")) {
        val secrets: List<Secret> = findResourcesByType()

        secrets.forEach { secret ->
            val objectAreaName = secret.metadata.name.substringBefore("-s3").replace("$appName-", "")
            assertThat(expectedObjectAreas).contains(objectAreaName)
            assertThat(secret.data.keys).containsAll(
                "serviceEndpoint",
                "accessKey",
                "secretKey",
                "bucketRegion",
                "bucketName",
                "objectPrefix"
            )

            val dc = find { it.resource is DeploymentConfig }?.let { it.resource as DeploymentConfig }
                ?: throw Exception("No dc")

            val container = dc.spec.template.spec.containers.first()
            val secretName = "$appName-$objectAreaName-s3"
            val objectAreaNameUpper = objectAreaName.toUpperCase()
            val actualS3Envs = container.env
                .map { envVar -> envVar.name to envVar.valueFrom.secretKeyRef.let { "${it.name}/${it.key}" } }
                .filter { (name, _) -> name.startsWith("S3") }

            val expectedEnvs = listOf(
                "S3_BUCKETS_${objectAreaNameUpper}_SERVICEENDPOINT" to "$secretName/serviceEndpoint",
                "S3_BUCKETS_${objectAreaNameUpper}_ACCESSKEY" to "$secretName/accessKey",
                "S3_BUCKETS_${objectAreaNameUpper}_SECRETKEY" to "$secretName/secretKey",
                "S3_BUCKETS_${objectAreaNameUpper}_BUCKETREGION" to "$secretName/bucketRegion",
                "S3_BUCKETS_${objectAreaNameUpper}_BUCKETNAME" to "$secretName/bucketName",
                "S3_BUCKETS_${objectAreaNameUpper}_OBJECTPREFIX" to "$secretName/objectPrefix"
            )
            assertThat(actualS3Envs).containsAll(*expectedEnvs.toTypedArray())

            val objectAreaS3EnvVars =
                actualS3Envs.filter { (name, _) -> name.startsWith("S3_BUCKETS_$objectAreaNameUpper") }
            assertThat(objectAreaS3EnvVars.size).isEqualTo(expectedEnvs.size)
        }
    }
}
