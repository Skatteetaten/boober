package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.isEqualTo
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3StorageGridProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SgProvisioningRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SgRequestsWithCredentials
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StorageGridCredentials
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class S3StorageGridFeatureTest : AbstractFeatureTest() {
    private val provisioner = mockk<S3StorageGridProvisioner>()

    override val feature: Feature
        get() = S3StorageGridFeature(provisioner, mockk())

    @AfterEach
    fun after() {
        HttpMock.clearAllHttpMocks()
    }

    val tenant = "paas-utv"
    val bucket1Name = "theBucket"

    val area1Name = "default"
    val area2Name = "min-andre-bucket"


    @Test
    fun `should fail with message when s3 bucketName is missing`() {
        assertThat {
            createAuroraDeploymentContext(
                """{ 
                "s3Defaults": {
                   "objectArea": "my-object-area"
                },
                "s3": true
           }"""
            )
        }.singleApplicationError("Missing field: bucketName for s3")
    }

    @Test
    fun `should fail with message when s3 objectArea is not according to required format`() {
        assertThat {
            createAuroraDeploymentContext(
                """{ 
                "s3Defaults": {
                   "objectArea": "my-object_Area",
                   "bucketName": "myBucket"
                },
                "s3": true
           }"""
            )
        }.singleApplicationError("s3 objectArea can only contain lower case characters,")
    }

    @Test
    fun `verify fails when tenant is not on required form`() {
        val tenant = "paas"
        assertThat {
            createAuroraDeploymentContext(
                """{ 
                "s3": {
                    "default" : {
                        "bucketName" : "anotherId",
                        "tenant": "$tenant"
                    }
                }
           }"""
            )
        }.singleApplicationError("Config for application simple in environment utv contains errors. s3 tenant must be on the form affiliation-cluster, specified value was: \"$tenant\".")
    }

    @Test
    fun `verify fails on validate when two identical objectareas in same bucket`() {
        assertThat {
            createAuroraDeploymentContext(
                """{ 
                "s3": {
                    "default" : {
                        "bucketName": "bucket1"
                    },
                    "another":{
                        "objectArea": "default",
                        "bucketName": "bucket1"
                    }
                }
           }"""
            )
        }.singleApplicationError("objectArea name=default used 2 times for same application")
    }

    @Test
    fun `verify setting tenant in AuroraConfig is overridden with default`() {
        val areaName = "default"
        val spec = createAuroraDeploymentContext(
            """{ 
                "s3": {
                    "$areaName" : {
                        "bucketName" : "anotherId",
                        "tenant": "demo-utv"
                    }
                }
           }"""
        ).spec
        assertThat(spec.s3ObjectAreas.find { it.area == areaName }?.tenant).isEqualTo("paas-utv")
    }

    @Test
    fun `verify is able to disable s3 when simple config`() {

        verify(exactly = 0) { provisioner.getOrProvisionCredentials(any(), any()) }

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
            provisioner.getOrProvisionCredentials(any(), match {
                it.size == 1 && it.find { it.bucketPostfix == bucket1Name && it.objectAreaName == area1Name } != null
            })
        } returns listOf(sgRequestsWithCredentials(area1Name, bucket1Name))

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
            provisioner.getOrProvisionCredentials(any(), match { r ->
                r.run {
                    find { it.bucketPostfix == bucket1Name && it.objectAreaName == area1Name } != null
                            && find { it.bucketPostfix == bucket2Name && it.objectAreaName == area2Name } != null
                }
            })
        } returns listOf(
            sgRequestsWithCredentials(area1Name, bucket1Name),
            sgRequestsWithCredentials(area2Name, bucket2Name)
        )

        val resources = generateResources(
            """{ 
                "s3Defaults": {
                    "bucketName": "$bucket1Name"
                },
                "s3": {
                    "$area1Name" : { },
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

        every { provisioner.getOrProvisionCredentials(any(), any()) } returns listOf(
            sgRequestsWithCredentials(area1Name, bucket1Name),
            sgRequestsWithCredentials(area2Name, bucket1Name)
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

    private fun sgRequestsWithCredentials(objectAreaName: String, bucketName: String) = SgRequestsWithCredentials(
        SgProvisioningRequest(tenant, objectAreaName, appName, kubeNs, this.bucket1Name),
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
