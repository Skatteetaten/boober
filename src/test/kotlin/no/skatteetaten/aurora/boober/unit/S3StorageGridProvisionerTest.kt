package no.skatteetaten.aurora.boober.unit

import io.mockk.mockk
import no.skatteetaten.aurora.boober.feature.OperationScopeFeature
import no.skatteetaten.aurora.boober.service.HerkimerService
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3StorageGridProvisioner
import org.junit.jupiter.api.Test

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
}
