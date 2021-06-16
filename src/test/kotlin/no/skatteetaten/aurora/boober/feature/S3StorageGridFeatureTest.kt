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
import no.skatteetaten.aurora.boober.service.resourceprovisioning.*
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
    val bucket1Name = "theBucket"

    val area1Name = "default"
    val area2Name = "min-andre-bucket"


    @Test
    fun `verify is able to disable s3 when simple config`() {
        generateResources(
            """{ 
                "s3": false
           }""",
            createdResources = 0,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )
    }

    @Test
    fun `verify is able to disable s3 when expanded config`() {

        every {
            s3StorageGridProvisioner.getOrProvisionCredentials(match { adc ->
                adc.s3ObjectAreas.run {
                    size == 1 && find { it.bucketName == bucket1Name && it.area == area1Name } != null
                }
            })
        } returns listOf(
            objectAreaWithCredentials(area1Name, bucket1Name),
        )
        val resources = generateResources(
            """{ 
                "s3": {
                    "$area1Name" : {
                        "bucketName": "$bucket1Name"
                    },
                    "$area2Name" : {
                        "enabled": false,
                        "bucketName" : "anotherId"
                    }
                }
           }""",
            createdResources = 1,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        resources.verifyS3SecretsAndEnvs(listOf(area1Name))
    }

    @Test
    fun `verify two objectareas with different bucketnames`() {
        val bucket2Name = "anotherBucket"

        every {
            s3StorageGridProvisioner.getOrProvisionCredentials(match { adc ->
                adc.s3ObjectAreas.run {
                    find { it.bucketName == bucket1Name && it.area == area1Name } != null
                            && find { it.bucketName == bucket2Name && it.area == area2Name } != null
                }
            })
        } returns listOf(
            objectAreaWithCredentials(area1Name, bucket1Name),
            objectAreaWithCredentials(area2Name, bucket2Name)
        )

        val resources = generateResources(
            """{ 
                "s3Defaults": {
                    "bucketName": "$bucket1Name"
                },
                "s3": {
                    "$area1Name" : {
                    },
                    "$area2Name" : {
                        "bucketName" : "$bucket2Name"
                    }
                }
           }""",
            createdResources = 2,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )
        resources.verifyS3SecretsAndEnvs(listOf(area1Name, area2Name))
    }

    @Test
    fun `creates secretes and environment variable refs for provisioned credentials`() {

        every { s3StorageGridProvisioner.getOrProvisionCredentials(any()) } returns listOf(
            objectAreaWithCredentials(area1Name, bucket1Name),
            objectAreaWithCredentials(area2Name, bucket1Name)
        )

        val resources = generateResources(
            """{ 
                "s3Defaults": {
                    "bucketName": "$bucket1Name"
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

    private fun objectAreaWithCredentials(objectAreaName: String, bucketName: String) = ObjectAreaWithCredentials(
        S3ObjectArea(tenant, this.bucket1Name, objectAreaName),
        StorageGridCredentials(
            tenant,
            "endpoint",
            "accessKey",
            "secretKey",
            this.bucket1Name,
            "objectPrefix",
            "username",
            "password"
        )
    )

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
