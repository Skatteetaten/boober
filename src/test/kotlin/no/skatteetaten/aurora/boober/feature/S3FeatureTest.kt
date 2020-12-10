package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import no.skatteetaten.aurora.boober.facade.json
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ResourceClaimHerkimer
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.resourceprovisioning.FionaRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.httpMockServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestTemplate
import java.time.LocalDateTime
import java.util.UUID

class S3FeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = S3Feature(
            s3Provisioner = s3Provisioner,
            herkimerService = herkimerService,
            cluster = "utv",
            booberApplicationdeploymentId = booberAdId,
            defaultBucketRegion = "us-east-1"
        )
    val booberAdId = "abc4567890"

    private val s3Provisioner = S3Provisioner(
        FionaRestTemplateWrapper(
            restTemplate = RestTemplate(),
            baseUrl = "http://localhost:5000",
            retries = 0
        )
    )
    private val herkimerService: HerkimerService = mockk()

    @AfterEach
    fun after() {
        HttpMock.clearAllHttpMocks()
    }

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
        mockHerkimer(
            booberAdId, true, s3Credentials = listOf(
                createS3Credentials("anotherId", "default"),
                createS3Credentials("minBucket", "hello-$environment")
            )
        )
        generateResources(
            """{ 
                "s3": {
                    "not-default" : {
                        "enabled": false,
                        "bucketName": "minBucket"
                    },
                    "default" : {
                        "bucketName" : "anotherId"
                    },
                    "substitutor":{
                         "bucketName":"minBucket",
                         "objectArea":"hello-@env@"
                    }
                }
           }""",
            createdResources = 2,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )
    }

    @Test
    fun `verify two objectareas with different bucketnames`() {
        val s3Credentials = listOf(
            createS3Credentials(bucketName = "minBucket", objectArea = "default"),
            createS3Credentials(bucketName = "anotherId", objectArea = "default")
        )

        mockHerkimer(
            booberAdId = booberAdId,
            claimExistsInHerkimer = true,
            s3Credentials = s3Credentials
        )

        generateResources(
            """{ 
                "s3": {
                    "not-default" : {
                        "bucketName": "minBucket",
                        "objectArea": "default" 
                    },
                    "default" : {
                        "bucketName" : "anotherId"
                    }
                }
           }""",
            createdResources = 2,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        ).verifyS3SecretsAndEnvs(expectedBucketObjectAreas = listOf("default", "not-default"))
    }

    @Test
    fun `verify creates secrets and supports custom bucketname suffix`() {
        val s3Credentials = listOf(
            createS3Credentials(),
            createS3Credentials(objectArea = "min-andre-bucket")
        )
        mockFiona()
        mockHerkimer(booberAdId = booberAdId, s3Credentials = s3Credentials, claimExistsInHerkimer = false)

        val resources = generateResources(
            """{ 
                "s3Defaults": {
                    "bucketName": "anotherId"
                },
                "s3": {
                    "default": {
                        "enabled": true
                    },
                    "min-andre-bucket": {
                        "enabled": true
                    }
                }
           }""",
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig()),
            createdResources = 2
        )

        resources.verifyS3SecretsAndEnvs(expectedBucketObjectAreas = listOf("default", "min-andre-bucket"))
    }

    @Test
    fun `verify fails when no bucket credentials are stored in herkimer`() {

        mockHerkimer(booberAdId = booberAdId, bucketIsRegisteredInHerkimer = false, claimExistsInHerkimer = false)

        assertThat {
            generateResources(
                """{ 
                "s3Defaults": {
                    "bucketName": "anotherId",
                    "objectArea": "default"
                },
                "s3": true
           }""",
                createdResources = 0,
                resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
            )
        }.singleApplicationError("Could not find credentials for bucket")
    }

    @Test
    fun `verify fails on validate when someone has claimed the bucketObjectArea`() {
        mockFiona()
        mockHerkimer(booberAdId = booberAdId, claimExistsInHerkimer = true, objectAreaIsAlreadyClaimed = true)

        assertThat {
            generateResources(
                """{ 
                "s3": {
                    "default" : {
                        "bucketName": "anotherId"
                    }
                }
           }""",
                createdResources = 0,
                resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
            )
        }.singleApplicationError("is already claimed")
    }

    @Test
    fun `Should fail with message when s3 bucketName is missing`() {
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
    fun `Should fail with message when s3 objectArea is not according to required format`() {
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
    fun `Should fail with message when s3 objectArea is missing`() {
        assertThat {
            createAuroraDeploymentContext(
                """{ 
                "s3Defaults": {
                   "bucketName": "anotherId"
                },
                "s3": true
           }"""
            )
        }.singleApplicationError("Missing field: objectArea for s3")
    }

    @Test
    fun `verify creates secret with value mappings in dc when claim exists in herkimer`() {
        mockFiona()
        mockHerkimer(booberAdId = booberAdId, claimExistsInHerkimer = true)

        val resources = generateResources(
            """{ 
                "s3": {
                    "default" : {
                        "bucketName": "anotherId"
                    }
                }
           }""",
            createdResources = 1,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        resources.verifyS3SecretsAndEnvs()
    }

    private fun mockFiona() {
        httpMockServer(5000) {
            rule {
                json(
                    """
                    {
                    "host": "http://fiona",
                    "accessKey": "accessKey",
                    "secretKey": "secretKey"
                    }
                """.trimIndent()
                )
            }
        }
    }

    private fun mockHerkimer(
        booberAdId: String,
        claimExistsInHerkimer: Boolean,
        s3Credentials: List<S3Credentials> = listOf(createS3Credentials()),
        objectAreaIsAlreadyClaimed: Boolean = false,
        bucketIsRegisteredInHerkimer: Boolean = true
    ) {
        val adId = "1234567890"

        val bucketNamesWithCredentials = s3Credentials.groupBy { it.bucketName }
        val bucketNames = bucketNamesWithCredentials.keys

        every {
            herkimerService.getClaimedResources(booberAdId, ResourceKind.MinioPolicy)
        } returns if (bucketIsRegisteredInHerkimer) {
            bucketNames.map {
                createResourceHerkimer(
                    name = it,
                    adId = booberAdId,
                    kind = ResourceKind.MinioPolicy,
                    claims = listOf(createResourceClaim(booberAdId))
                )
            }
        } else emptyList()

        s3Credentials.forEach {
            val s3BucketObjectAreaResourceName = "${it.bucketName}/${it.objectArea}"

            every {
                herkimerService.getClaimedResources(
                    claimOwnerId = null,
                    resourceKind = ResourceKind.MinioObjectArea,
                    name = s3BucketObjectAreaResourceName
                )
            } returns if (objectAreaIsAlreadyClaimed) listOf(
                createS3BucketObjectAreaResourceForCredentials(
                    adId = adId,
                    s3BucketObjectAreaResourceName = s3BucketObjectAreaResourceName,
                    credentials = it,
                    claimOwnerId = UUID.randomUUID().toString()
                )
            ) else emptyList()
        }

        every {
            herkimerService.getClaimedResources(
                claimOwnerId = adId,
                resourceKind = ResourceKind.MinioObjectArea
            )
        } returns if (claimExistsInHerkimer) s3Credentials.map {
            createS3BucketObjectAreaResourceForCredentials(
                adId = adId,
                s3BucketObjectAreaResourceName = "${it.bucketName}/${it.objectArea}",
                credentials = it,
                claimOwnerId = adId
            )
        } else emptyList()

        if (!claimExistsInHerkimer) {
            every {
                herkimerService.createResourceAndClaim(any(), any(), any(), any(), any())
            } just Runs
        }
    }

    private fun createS3BucketObjectAreaResourceForCredentials(
        adId: String,
        s3BucketObjectAreaResourceName: String,
        credentials: S3Credentials,
        claimOwnerId: String
    ): ResourceHerkimer {
        return createResourceHerkimer(
            adId = adId,
            kind = ResourceKind.MinioObjectArea,
            name = s3BucketObjectAreaResourceName,
            claims = listOf(
                createResourceClaim(
                    adId = claimOwnerId,
                    s3Credentials = jacksonObjectMapper().convertValue(credentials)
                )
            )
        )
    }

    private fun List<AuroraResource>.verifyS3SecretsAndEnvs(expectedBucketObjectAreas: List<String> = listOf("default")) {
        val secrets: List<Secret> = findResourcesByType()

        secrets.forEach { secret ->
            val bucketObjectArea = secret.metadata.name.substringBefore("-s3").replace("$appName-", "")
            assertThat(expectedBucketObjectAreas).contains(bucketObjectArea)
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
            val secretName = "$appName-$bucketObjectArea-s3"
            val bucketObjectAreaUpper = bucketObjectArea.toUpperCase()
            val actualEnvs = container.env
                .map { envVar -> envVar.name to envVar.valueFrom.secretKeyRef.let { "${it.name}/${it.key}" } }
                .filter { (name, _) ->
                    name.startsWith("S3")
                }

            val expectedEnvs = listOf(
                "S3_BUCKETS_${bucketObjectAreaUpper}_SERVICEENDPOINT" to "$secretName/serviceEndpoint",
                "S3_BUCKETS_${bucketObjectAreaUpper}_ACCESSKEY" to "$secretName/accessKey",
                "S3_BUCKETS_${bucketObjectAreaUpper}_SECRETKEY" to "$secretName/secretKey",
                "S3_BUCKETS_${bucketObjectAreaUpper}_BUCKETREGION" to "$secretName/bucketRegion",
                "S3_BUCKETS_${bucketObjectAreaUpper}_BUCKETNAME" to "$secretName/bucketName",
                "S3_BUCKETS_${bucketObjectAreaUpper}_OBJECTPREFIX" to "$secretName/objectPrefix"
            )
            assertThat(actualEnvs).containsAll(*expectedEnvs.toTypedArray())

            val objectAreaS3EnvVars =
                actualEnvs.filter { (name, _) -> name.startsWith("S3_BUCKETS_$bucketObjectAreaUpper") }
            assertThat(objectAreaS3EnvVars.size).isEqualTo(expectedEnvs.size)
        }
    }

    private fun createS3Credentials(bucketName: String = "anotherId", objectArea: String = "default") =
        S3Credentials(
            objectArea,
            "http://localhost",
            "access",
            "secret",
            bucketName,
            "objectprefix",
            "us-east-1"

        )

    private fun createResourceHerkimer(
        adId: String,
        kind: ResourceKind,
        name: String = "paas-bucket-u-default",
        claims: List<ResourceClaimHerkimer>? = null
    ) = ResourceHerkimer(
        id = "0",
        name = name,
        kind = kind,
        ownerId = adId,
        claims = claims,
        createdDate = LocalDateTime.now(),
        modifiedDate = LocalDateTime.now(),
        createdBy = "aurora",
        modifiedBy = "aurora",
        parentId = null
    )

    private fun createResourceClaim(
        adId: String,
        s3Credentials: JsonNode = jacksonObjectMapper().createObjectNode()
    ) =
        ResourceClaimHerkimer(
            id = "0L",
            ownerId = adId,
            resourceId = 0L,
            credentials = s3Credentials,
            createdDate = LocalDateTime.now(),
            modifiedDate = LocalDateTime.now(),
            createdBy = "aurora",
            modifiedBy = "aurora"
        )
}
