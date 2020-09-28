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
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.MultiApplicationValidationException
import no.skatteetaten.aurora.boober.service.ResourceClaimHerkimer
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningResult
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.findResourceByType
import org.junit.jupiter.api.Test
import java.lang.IllegalArgumentException
import java.time.LocalDateTime

class S3FeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = S3Feature(
            s3Provisioner,
            herkimerService,
            ProductionLevels(mapOf("utv" to ProductionLevels.ProductionLevel.Utvikling)),
            booberAdId
        )
    val booberAdId = "abc4567890"

    private val s3Provisioner: S3Provisioner = mockk()
    private val herkimerService: HerkimerService = mockk()

    @Test
    fun `verify creates secret with value mappings in dc when claim does not exist in herkimer`() {
        mockHerkimer(booberAdId = booberAdId, claimExistsInHerkimer = false)

        val resources = generateResources(
            """{ 
               "beta" : {
                   "s3": true
               }
           }""",
            createdResources = 1,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        resources.validateS3SecretAndEnvs()
    }

    @Test
    fun `verify fails when no bucket credentials are stored in herkimer`() {

        every { herkimerService.getClaimedResources(booberAdId, ResourceKind.MinioPolicy, any()) } returns emptyList()
        assertThat {
            generateResources(
                """{ 
               "beta" : {
                   "s3": true
               }
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
        every { s3Provisioner.provision(any()) } returns s3ProvisioningResult
        mockHerkimer(booberAdId, true)

        val resources = generateResources(
            """{ 
               "beta" : {
                   "s3": true
               }
           }""",
            createdResources = 1,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        resources.validateS3SecretAndEnvs()
    }

    private val s3ProvisioningResult = S3ProvisioningResult(
        serviceEndpoint = "http://locahost:9000",
        accessKey = "accessKey",
        secretKey = "secretKey",
        bucketName = "default",
        objectPrefix = appName,
        bucketRegion = "us-east-1"
    )

    private fun mockHerkimer(booberAdId: String, claimExistsInHerkimer: Boolean) {
        val adId = "1234567890"

        every {
            herkimerService.getClaimedResources(booberAdId, ResourceKind.MinioPolicy, any())
        } returns listOf(
            createResourceHerkimer(
                adId = booberAdId,
                claims = createResourceClaim(
                    adId = booberAdId,
                    s3ProvisioningResult = jacksonObjectMapper().createObjectNode()
                )
            )
        )
        if (claimExistsInHerkimer) {
            every { herkimerService.getClaimedResources(adId, ResourceKind.MinioPolicy) } returns listOf(
                createResourceHerkimer(
                    adId = adId,
                    claims = createResourceClaim(
                        adId = adId,
                        s3ProvisioningResult = jacksonObjectMapper().convertValue(s3ProvisioningResult)
                    )
                )
            )
        } else {
            every { herkimerService.getClaimedResources(adId, ResourceKind.MinioPolicy) } returns emptyList()
            every {
                herkimerService.createResourceAndClaim(
                    ownerId = adId,
                    resourceKind = ResourceKind.MinioPolicy,
                    resourceName = s3ProvisioningResult.bucketName,
                    credentials = s3ProvisioningResult
                )
            } just Runs
        }
    }

    private fun List<AuroraResource>.validateS3SecretAndEnvs() {
        val secret: Secret = findResourceByType()

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

    private fun createResourceHerkimer(
        adId: String,
        claims: List<ResourceClaimHerkimer>? = null
    ) = ResourceHerkimer(
        id = "0",
        name = "myResource",
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
