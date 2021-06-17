package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.feature.OperationScopeFeature
import no.skatteetaten.aurora.boober.feature.S3FeatureTemplate
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ResourceClaimHerkimer
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind.StorageGridObjectArea
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ObjectAreaWithCredentials
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3StorageGridProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StorageGridCredentials
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import org.junit.jupiter.api.Test
import java.time.LocalDateTime.now

class S3StorageGridProvisionerTest : AbstractFeatureTest() {

    val herkimerService = mockk<HerkimerService>()

    val provisioner = S3StorageGridProvisioner(
        mockk(),
        mockk(),
        mockk(),
        herkimerService,
        OperationScopeFeature("utv"),
        "us-east-1",
        20000,
        1000
    )

    @Test
    fun `does nothing when s3 is disabled`() {

        provisioner.getOrProvisionCredentials(ads("""{ }"""))
        provisioner.getOrProvisionCredentials(ads("""{ "s3": false }"""))
    }

    @Test
    fun `does not provision for existing claims`() {

        val objectAreaName = "default"
        every { herkimerService.getClaimedResources(any(), StorageGridObjectArea) } returns
                listOf(sgAdminCreds(objectAreaName))

        fun List<ObjectAreaWithCredentials>.validate() {
            assertThat(this.size).isEqualTo(1)
            first().let { (objectArea) ->
                assertThat(objectArea.area).isEqualTo(objectAreaName)
            }
        }
        provisioner.getOrProvisionCredentials(ads("""{ "s3": true }"""))
            .validate()
        provisioner.getOrProvisionCredentials(ads("""{ "s3": { "$objectAreaName": { "enabled": true } } }"""))
            .validate()
        provisioner.getOrProvisionCredentials(ads("""{ "s3": { "custom": { "enabled": true, "objectArea": "$objectAreaName" } } }"""))
            .validate()
    }

    private fun ads(appFile: String): AuroraDeploymentSpec =
        createAuroraDeploymentContext(appFile, files = emptyList()).spec

    override val feature: Feature
        get() = object : S3FeatureTemplate() {}
}

private fun sgAdminCreds(objectAreaName: String): ResourceHerkimer {
    val sgc = StorageGridCredentials("", "", "", "", "", "", "", "", null)
    val sgcJsonNode = jacksonObjectMapper().readTree(jacksonObjectMapper().writeValueAsString(sgc))
    return ResourceHerkimer(
        "", objectAreaName, StorageGridObjectArea, "", listOf(
            ResourceClaimHerkimer("", "", 1, sgcJsonNode, "ADMIN", now(), now(), "", "")
        ), null, now(), now(), "", ""
    )
}
