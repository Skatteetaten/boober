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
import no.skatteetaten.aurora.boober.facade.json
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ResourceClaimHerkimer
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind
import no.skatteetaten.aurora.boober.service.resourceprovisioning.FionaRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Access
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningResult
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.findResourceByType
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.httpMockServer
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestTemplate
import java.time.LocalDateTime

class S3FeatureTestClaimExists : S3FeatureTest(ClaimInHerkimer.CLAIM_EXISTS)
class S3FeatureTestClaimNotExists : S3FeatureTest(ClaimInHerkimer.CLAIM_NOT_EXISTS)

enum class ClaimInHerkimer { CLAIM_EXISTS, CLAIM_NOT_EXISTS }

abstract class S3FeatureTest(val claimExistsInHerkimer: ClaimInHerkimer) : AbstractFeatureTest() {
    override val feature: Feature
        get() = S3Feature(s3Provisioner, herkimerService)

    private val s3Provisioner = S3Provisioner(
        FionaRestTemplateWrapper(
            restTemplate = RestTemplate(),
            baseUrl = "http://localhost:5000",
            retries = 0
        ),
        "us-east-1"
    )
    private val herkimerService: HerkimerService = mockk()

    @Test
    fun `verify creates secret with value mappings in dc`() {

        val adId = "1234567890"
        val bucketName = "$affiliation-bucket-t-$cluster-default"

        httpMockServer(5000) {
            rule {
                json("""
                    {
                    "host": "http://fiona",
                    "accessKey": "accessKey",
                    "secretKey": "secretKey"
                    }
                """.trimIndent())
            }
        }

        val s3ProvisioningResult = S3ProvisioningResult(
            S3ProvisioningRequest(
                bucketName = bucketName,
                path = adId,
                userName = adId,
                access = listOf(S3Access.WRITE, S3Access.DELETE, S3Access.READ)
            ),
            "http://locahost:9000",
            "accessKey",
            "secretKey",
            "default",
            appName,
            "us-east-1"
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
            every { herkimerService.getClaimedResources(any(), any()) } returns listOf(
                createResourceHerkimer(adId, resourceClaimHerkimer)
            )
        } else {
            every { herkimerService.getClaimedResources(any(), any()) } returns emptyList()
            every { herkimerService.createResourceAndClaim(any(), any(), any(), any()) } just Runs
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

        resources.verifyS3SecretsAndEnvs()
    }

    private fun List<AuroraResource>.verifyS3SecretsAndEnvs() {
        val secret: Secret = this.findResourceByType()

        assertThat(secret.data.keys).containsAll(
            "serviceEndpoint",
            "accessKey",
            "secretKey",
            "bucketRegion",
            "bucketName",
            "objectPrefix"
        )

        val dc = this.find { it.resource is DeploymentConfig }?.let { it.resource as DeploymentConfig }
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
