package no.skatteetaten.aurora.boober.unit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import no.skatteetaten.aurora.boober.feature.OperationScopeFeature
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.ResourceClaimHerkimer
import no.skatteetaten.aurora.boober.service.ResourceHerkimer
import no.skatteetaten.aurora.boober.service.ResourceKind.StorageGridObjectArea
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3StorageGridProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StorageGridCredentials
import org.junit.jupiter.api.Test
import java.time.LocalDateTime.now
import kotlin.random.Random

class S3StorageGridProvisionerTest {

    val herkimerService = mockk<HerkimerService>()

    val provisioner = S3StorageGridProvisioner(
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

        provisioner.getOrProvisionCredentials("", emptyList())
    }

    /*
    @Test
    fun `does not provision for existing claims`() {

        val aId = "some_id"
        val objectAreaName = "default"
        val requests = listOf(SgProvisioningRequest("", objectAreaName, "", "", ""))

        every { herkimerService.getClaimedResources(aId, StorageGridObjectArea) } returns
            listOf(sgAdminCreds(aId, objectAreaName))

        val response = provisioner.getOrProvisionCredentials(aId, requests)
        assertThat(response.credentials).hasSize(1)
    }
     */
}

private fun sgAdminCreds(aId: String, objectAreaName: String): ResourceHerkimer {

    val sgc = StorageGridCredentials("", "", "", "", "", "", "", "", null)
    val sgcJsonNode = jacksonObjectMapper().readTree(jacksonObjectMapper().writeValueAsString(sgc))
    val resourceId = Random.nextLong(0, 1000)
    val credId = Random.nextLong(0, 1000)
    val user = "aurora"
    val created = now()
    return ResourceHerkimer(
        "$resourceId", objectAreaName, StorageGridObjectArea, aId,
        listOf(
            ResourceClaimHerkimer(
                credId.toString(),
                aId,
                resourceId,
                sgcJsonNode,
                "ADMIN",
                created,
                created,
                user,
                user
            )
        ),
        null, created, created, user, user
    )
}
