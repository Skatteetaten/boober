package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import com.fkorotkov.kubernetes.newObjectMeta
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectArea
import no.skatteetaten.aurora.boober.model.openshift.StorageGridObjectAreaSpec
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3StorageGridProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SgProvisioningRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SgRequestsWithCredentials
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SgoaWithCredentials
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StorageGridCredentials
import no.skatteetaten.aurora.boober.utils.AbstractMultiFeatureTest
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import no.skatteetaten.aurora.boober.utils.singleApplicationErrorResult
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock

class S3StorageGridFeatureTest : AbstractMultiFeatureTest() {
    private val provisioner = mockk<S3StorageGridProvisioner>()

    override val features: List<Feature>
        get() = listOf(
            S3StorageGridFeature(provisioner, mockk())
        )

    @AfterEach
    fun after() {
        HttpMock.clearAllHttpMocks()
    }

    val tenant = "paas-utv"
    val bucket1Name = "thebucket"

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
        }.singleApplicationErrorResult("Missing field: bucketName for s3")
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
        }.singleApplicationErrorResult("Config for application simple in environment utv contains errors. s3 tenant must be on the form affiliation-cluster, specified value was: \"$tenant\".")
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
        }.singleApplicationErrorResult("objectArea name=default used 2 times for same application")
    }

    @Test
    fun `verify fails on validate when bucketName contains illegal chars`() {
        val bucketName = "Bucket-1"
        assertThat {
            createAuroraDeploymentContext(
                """{ 
                "s3": {
                    "default" : {
                        "bucketName": "$bucketName"
                    }
                }
           }"""
            )
        }.singleApplicationErrorResult("s3 bucketName can only contain lower case characters, numbers, hyphen(-) or period(.), specified value was: \"$bucketName\"")
    }

    @Test
    fun `verify fails on validate when combination of bucketName and tenant is too long`() {
        val bucketName = "bucket-1-bit-extra-here-to-get-the-bucket-name-too-long-abcdefghijk"
        assertThat {
            createAuroraDeploymentContext(
                """{ 
                "s3": {
                    "default" : {
                        "bucketName": "$bucketName"
                    }
                }
           }"""
            )
        }.singleApplicationErrorResult("combination of bucketName and tenantName must be between 3 and 63 chars, specified value was 76 chars long")
    }

    @Test
    fun `verify setting tenant in AuroraConfig is overridden with default`() {
        val areaName = "default"
        val (valid, _) = createAuroraDeploymentContext(
            """{ 
                "s3": {
                    "$areaName" : {
                        "bucketName" : "anotherid",
                        "tenant": "demo-utv"
                    }
                }
           }"""
        )
        assertThat(valid.first().spec.s3ObjectAreas.find { it.area == areaName }?.tenant).isEqualTo("paas-utv")
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
            provisioner.getOrProvisionCredentials(
                any(),
                match {
                    it.size == 1 && it.find { it.bucketPostfix == bucket1Name && it.objectAreaName == area1Name } != null
                }
            )
        } returns SgoaWithCredentials(
            emptyList(), listOf(sgRequestsWithCredentials(area1Name, bucket1Name))
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
        val bucket2Name = "anotherbucket"

        every {
            provisioner.getOrProvisionCredentials(
                any(),
                match { r ->
                    r.run {
                        find { it.bucketPostfix == bucket1Name && it.objectAreaName == area1Name } != null &&
                            find { it.bucketPostfix == bucket2Name && it.objectAreaName == area2Name } != null
                    }
                }
            )
        } returns SgoaWithCredentials(
            emptyList(),
            listOf(
                sgRequestsWithCredentials(area1Name, bucket1Name),
                sgRequestsWithCredentials(area2Name, bucket2Name)
            )
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

        every { provisioner.getOrProvisionCredentials(any(), any()) } returns SgoaWithCredentials(
            emptyList(),
            listOf(
                sgRequestsWithCredentials(area1Name, bucket1Name),
                sgRequestsWithCredentials(area2Name, bucket1Name)
            )
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

    @Test
    fun `verify OwnerReference and CommonLabels are included in StorageGridObjectArea`() {
        every {
            provisioner.getOrProvisionCredentials(
                any(),
                any()
            )
        } returns SgoaWithCredentials(
            listOf(
                StorageGridObjectArea(
                    _metadata = newObjectMeta {
                        name = "name"
                        namespace = "namespace"
                    },
                    spec = StorageGridObjectAreaSpec("test", "123", area1Name, false)
                )
            ),
            listOf(
                sgRequestsWithCredentials(area1Name, bucket1Name),
            )
        )

        val resources = generateResources(
            """{ 
                "s3": {
                    "$area1Name" : {
                        "bucketName": "$bucket1Name"
                    }
                }
           }""",
            createdResources = 2,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        val storageGridObjectArea = resources.find { it.resource is StorageGridObjectArea }

        assertThat { storageGridObjectArea?.resource?.metadata?.ownerReferences }.isNotNull()
        assertThat { storageGridObjectArea?.resource?.metadata?.labels }.isNotNull()
    }

    private fun sgRequestsWithCredentials(objectAreaName: String, bucketName: String) = SgRequestsWithCredentials(
        SgProvisioningRequest(tenant, objectAreaName, appName, kubeNs, bucketName),
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
            val objectAreaNameUpper = objectAreaName.uppercase()
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
