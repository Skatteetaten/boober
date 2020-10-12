package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
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
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.service.ResourceClaimHerkimer
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.resourceprovisioning.FionaRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.httpMockServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestTemplate
import java.lang.IllegalArgumentException
import java.time.LocalDateTime

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
    fun `verify supports beta block S3`() {
        mockFiona()
        mockHerkimer(booberAdId = booberAdId, claimExistsInHerkimer = false)

        generateResources(
            """{ 
                "beta": {
                    "s3": true
                }
           }""",
            createdResources = 1,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        ).verifyS3SecretsAndEnvs()
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
        generateResources(
            """{ 
                "s3": {
                    "default" : {
                        "enabled": false,
                        "name": "minBucket"
                    }
                }
           }""",
            createdResources = 0,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )
    }

    @Test
    fun `verify creates secrets and supports custom bucketname suffix`() {
        val s3Credentials = listOf(
            S3Credentials(
                "default",
                "http://localhost",
                "accessKey",
                "secretKey",
                "uniqueId",
                "uniquePrefix",
                "us-east-1"
            ),
            S3Credentials(
                "minAndreBucket",
                "http://localhost",
                "accessKey",
                "secretKey",
                "uniqueId",
                "uniquePrefix",
                "us-east-1"
            )
        )
        mockFiona()
        mockHerkimer(booberAdId = booberAdId, s3Credentials = s3Credentials, claimExistsInHerkimer = false)

        val resources = generateResources(
            """{ 
                "s3Defaults": {
                    "bucketName": "uniqueId"
                },
                "s3": {
                    "default": {
                        "enabled": true
                    },
                    "minAndreBucket": {
                        "enabled": true
                    }
                }
           }""",
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig()),
            createdResources = 2
        )

        resources.verifyS3SecretsAndEnvs(expectedBucketObjectAreas = listOf("default", "minAndreBucket"))
    }

    @Test
    fun `verify fails when no bucket credentials are stored in herkimer`() {

        every { herkimerService.getClaimedResources(booberAdId, ResourceKind.MinioPolicy, any()) } returns emptyList()
        assertThat {
            generateResources(
                """{ 
                "s3": true
           }""",
                createdResources = 0,
                resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
            )
        }.isFailure().isInstanceOf(MultiApplicationValidationException::class).given { validationEx ->
            assertThat(validationEx.errors.first().errors.first()).isInstanceOf(IllegalArgumentException::class).given {
                assertThat(it.message).isNotNull().contains("Could not find credentials for bucket")
            }
        }
    }

    @Test
    fun `verify creates secret with value mappings in dc when claim exists in herkimer`() {
        mockFiona()
        mockHerkimer(booberAdId = booberAdId, claimExistsInHerkimer = true)

        val resources = generateResources(
            """{ 
                "s3": true
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
        s3Credentials: List<S3Credentials> = listOf(
            S3Credentials(
                "default",
                "http://localhost",
                "accesKey",
                "secretKey",
                "anotherId",
                "objectprefix",
                "us-east-1"
            )
        )
    ) {
        val adId = "1234567890"

        val bucketNamesWithCredentials = s3Credentials.groupBy { it.bucketName }
        val bucketNames = bucketNamesWithCredentials.keys
        every {
            herkimerService.getClaimedResources(booberAdId, ResourceKind.MinioPolicy)
        } returns bucketNames.map {
            createResourceHerkimer(
                name = it,
                adId = booberAdId,
                claims = createResourceClaim(
                    adId = booberAdId,
                    s3Credentials = jacksonObjectMapper().createObjectNode()
                )
            )
        }

        if (claimExistsInHerkimer) {
            every { herkimerService.getClaimedResources(adId, ResourceKind.MinioPolicy) } returns bucketNames.map {
                createResourceHerkimer(
                    adId = adId,
                    name = it,
                    claims = createResourceClaim(
                        adId = adId,
                        s3Credentials = jacksonObjectMapper().convertValue(
                            bucketNamesWithCredentials[it]
                                ?: throw RuntimeException("Failed test, could not find credentials for bucketname=$it")
                        )
                    )
                )
            }
        } else {
            every { herkimerService.getClaimedResources(adId, ResourceKind.MinioPolicy, any()) } returns emptyList()
            every {
                herkimerService.createResourceAndClaim(any(), any(), any(), any())
            } just Runs
        }
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
            val actualEnvs = container.env
                .map { envVar -> envVar.name to envVar.valueFrom.secretKeyRef.let { "${it.name}/${it.key}" } }

            val secretName = "$appName-$bucketObjectArea-s3"
            val bucketObjectAreaUpper = bucketObjectArea.toUpperCase()
            val expectedEnvs = listOf(
                "S3_${bucketObjectAreaUpper}_SERVICEENDPOINT" to "$secretName/serviceEndpoint",
                "S3_${bucketObjectAreaUpper}_ACCESSKEY" to "$secretName/accessKey",
                "S3_${bucketObjectAreaUpper}_SECRETKEY" to "$secretName/secretKey",
                "S3_${bucketObjectAreaUpper}_BUCKETREGION" to "$secretName/bucketRegion",
                "S3_${bucketObjectAreaUpper}_BUCKETNAME" to "$secretName/bucketName",
                "S3_${bucketObjectAreaUpper}_OBJECTPREFIX" to "$secretName/objectPrefix"
            )
            assertThat(actualEnvs).containsAll(*expectedEnvs.toTypedArray())
        }
    }

    private fun createResourceHerkimer(
        adId: String,
        name: String = "paas-bucket-u-default",
        claims: List<ResourceClaimHerkimer>? = null
    ) = ResourceHerkimer(
        id = "0",
        name = name,
        kind = ResourceKind.MinioPolicy,
        ownerId = adId,
        claims = claims,
        createdDate = LocalDateTime.now(),
        modifiedDate = LocalDateTime.now(),
        createdBy = "aurora",
        modifiedBy = "aurora"
    )

    private fun createResourceClaim(
        adId: String,
        s3Credentials: JsonNode
    ) = listOf(
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
    )
}
