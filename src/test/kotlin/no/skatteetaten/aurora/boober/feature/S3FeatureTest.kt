package no.skatteetaten.aurora.boober.feature

import assertk.assertThat
import assertk.assertions.containsAll
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.ApplicationDeploymentCreateRequest
import no.skatteetaten.aurora.boober.service.ApplicationDeploymentHerkimer
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ResourceClaimHerkimer
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Access
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningResult
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.findResourceByType
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class S3FeatureTestClaimExists : S3FeatureTest(ClaimInHerkimer.CLAIM_EXISTS)
class S3FeatureTestClaimNotExists : S3FeatureTest(ClaimInHerkimer.CLAIM_NOT_EXISTS)

enum class ClaimInHerkimer { CLAIM_EXISTS, CLAIM_NOT_EXISTS }

abstract class S3FeatureTest(val claimExistsInHerkimer: ClaimInHerkimer) : AbstractFeatureTest() {
    val booberAdId = "0123456789"
    override val feature: Feature
        get() = S3Feature(s3Provisioner, herkimerService, booberAdId)

    private val s3Provisioner: S3Provisioner = mockk()
    private val herkimerService: HerkimerService = mockk()

    @Test
    fun `verify creates secret with value mappings in dc`() {

        val adId = "1234567890"
        val bucketName = "${affiliation}_bucket_t_${cluster}_default"
        val request = S3ProvisioningRequest(
            bucketName = bucketName,
            path = adId,
            minioConnectInfoJsonNode = mapper.createObjectNode(),
            userName = adId,
            access = listOf(S3Access.WRITE, S3Access.DELETE, S3Access.READ)
        )
        val adPayload = ApplicationDeploymentCreateRequest(
            name = appName,
            environmentName = environment,
            cluster = cluster,
            businessGroup = affiliation
        )
        val s3ProvisioningResult = S3ProvisioningResult(
            request,
            "http://locahost:9000",
            "accessKey",
            "secretKey",
            "default",
            appName,
            "us-east-1"
        )
        every { s3Provisioner.provision(request) } returns s3ProvisioningResult

        val booberAdminClaim = listOf(
            ResourceClaimHerkimer(
                "0",
                booberAdId,
                0L,
                mapper.createObjectNode(),
                LocalDateTime.now(),
                LocalDateTime.now(),
                "aurora",
                "aurora"
            )
        )

        every {
            herkimerService.getClaimedResources(booberAdId, ResourceKind.MinioPolicy, bucketName)
        } returns listOf(
            createResourceHerkimer(booberAdId, booberAdminClaim)
        )

        every { herkimerService.createApplicationDeployment(adPayload) } returns ApplicationDeploymentHerkimer(
            id = adId,
            name = appName,
            environmentName = environment,
            cluster = cluster,
            businessGroup = affiliation,
            createdDate = LocalDateTime.now(),
            modifiedDate = LocalDateTime.now(),
            createdBy = "aurora",
            modifiedBy = "aurora"
        )
        if (claimExistsInHerkimer == ClaimInHerkimer.CLAIM_EXISTS) {
            val resourceClaimHerkimer = listOf(
                ResourceClaimHerkimer(
                    "0L",
                    adId,
                    0L,
                    jacksonObjectMapper().convertValue(s3ProvisioningResult),
                    LocalDateTime.now(),
                    LocalDateTime.now(),
                    "aurora",
                    "aurora"
                )
            )
            every { herkimerService.getClaimedResources(adId, ResourceKind.MinioPolicy) } returns listOf(
                createResourceHerkimer(adId, resourceClaimHerkimer)
            )
        } else {
            every { herkimerService.getClaimedResources(adId, ResourceKind.MinioPolicy) } returns emptyList()
            every {
                herkimerService.createResourceAndClaim(
                    adId,
                    ResourceKind.MinioPolicy,
                    s3ProvisioningResult.bucketName,
                    s3ProvisioningResult
                )
            } just Runs
        }

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

        assertThat(secret.data.keys).containsAll(
            "serviceEndpoint",
            "accessKey",
            "secretKey",
            "bucketRegion",
            "bucketName",
            "objectPrefix"
        )

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

    private fun createResourceHerkimer(
        adId: String,
        claims: List<ResourceClaimHerkimer>? = null
    ): ResourceHerkimer {
        return ResourceHerkimer(
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
    }
}
