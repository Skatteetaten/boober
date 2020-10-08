package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAll
import assertk.assertions.isEqualTo
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
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningResult
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.findResourcesByType
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.httpMockServer
import org.apache.commons.codec.binary.Base64
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
            productionLevel = "u",
            booberApplicationdeploymentId = booberAdId
        )
    val booberAdId = "abc4567890"

    private val s3Provisioner = S3Provisioner(
        FionaRestTemplateWrapper(
            restTemplate = RestTemplate(),
            baseUrl = "http://localhost:5000",
            retries = 0
        ),
        "us-east-1"
    )
    val defaultBucketName = "$affiliation-bucket-u-default"
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
        mockFiona()
        mockHerkimer(booberAdId = booberAdId, bucketNames = listOf("$affiliation-bucket-u-minBucket", "$affiliation-bucket-u-minAndreBucket"), claimExistsInHerkimer = false)

        val resources = generateResources(
            """{ 
                "s3": {
                    "default": {
                        "name": "minBucket"
                    },
                    "minAndreBucket": {
                        "enabled": true
                    }
                }
           }""",
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig()),
            createdResources = 2
        )

        resources.verifyS3SecretsAndEnvs(expectedBucketNameSuffixes = listOf("minBucket", "minAndreBucket"))
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

    private fun mockHerkimer(booberAdId: String, bucketNames: List<String> = listOf("paas-bucket-u-default"), claimExistsInHerkimer: Boolean) {
        val adId = "1234567890"

        every {
            herkimerService.getClaimedResources(booberAdId, ResourceKind.MinioPolicy)
        } returns bucketNames.map {
            createResourceHerkimer(
                name = it,
                adId = booberAdId,
                claims = createResourceClaim(
                    adId = booberAdId,
                    s3ProvisioningResult = jacksonObjectMapper().createObjectNode()
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
                        s3ProvisioningResult = jacksonObjectMapper().convertValue(
                            S3ProvisioningResult(
                                serviceEndpoint = "http://locahost:9000",
                                accessKey = "accessKey",
                                secretKey = "secretKey",
                                bucketName = defaultBucketName,
                                objectPrefix = appName,
                                bucketRegion = "us-east-1"
                            )
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

    private fun List<AuroraResource>.verifyS3SecretsAndEnvs(expectedBucketNameSuffixes: List<String> = listOf("default")) {
        val secrets: List<Secret> = findResourcesByType()

        secrets.forEach { secret ->
            val bucketName = String(Base64.decodeBase64(secret.data["bucketName"]))
            val expectedBucketNameSuffix = expectedBucketNameSuffixes.find { bucketSuffix -> bucketName.contains(bucketSuffix) }
            assertThat(expectedBucketNameSuffix).isNotNull().given { bucketSuffix ->
                assertThat(bucketName).isEqualTo("$affiliation-bucket-u-$bucketSuffix")
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
                val actualEnvs =
                    container.env.map { envVar -> envVar.name to envVar.valueFrom.secretKeyRef.let { "${it.name}/${it.key}" } }
                val secretName = "$appName-$bucketSuffix-s3"
                val bucketNameSuffixUpper = bucketSuffix.toUpperCase()
                val expectedEnvs = listOf(
                    "S3_${bucketNameSuffixUpper}_SERVICEENDPOINT" to "$secretName/serviceEndpoint",
                    "S3_${bucketNameSuffixUpper}_ACCESSKEY" to "$secretName/accessKey",
                    "S3_${bucketNameSuffixUpper}_SECRETKEY" to "$secretName/secretKey",
                    "S3_${bucketNameSuffixUpper}_BUCKETREGION" to "$secretName/bucketRegion",
                    "S3_${bucketNameSuffixUpper}_BUCKETNAME" to "$secretName/bucketName",
                    "S3_${bucketNameSuffixUpper}_OBJECTPREFIX" to "$secretName/objectPrefix"
                )
                assertThat(actualEnvs).containsAll(*expectedEnvs.toTypedArray())
            }
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
        s3ProvisioningResult: JsonNode
    ) = listOf(
        ResourceClaimHerkimer(
            id = "0L",
            ownerId = adId,
            resourceId = 0L,
            credentials = s3ProvisioningResult,
            createdDate = LocalDateTime.now(),
            modifiedDate = LocalDateTime.now(),
            createdBy = "aurora",
            modifiedBy = "aurora"
        )
    )
}
