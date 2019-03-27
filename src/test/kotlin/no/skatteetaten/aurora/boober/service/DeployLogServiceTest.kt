package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.skatteetaten.aurora.boober.model.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.utils.jsonMapper

import org.junit.jupiter.api.Test

class DeployLogServiceTest : AbstractAuroraConfigTest() {

    @Test
    fun `Should mark release`() {

        val bitbucketService = mockk<BitbucketService>()
        val service = DeployLogService(bitbucketService, jsonMapper(), "ao", "auroradeploymenttags")

        val auroraConfigRef = AuroraConfigRef("test", "master", "123")
        val applicationDeploymentRef = ApplicationDeploymentRef("foo", "bar")
        val command = ApplicationDeploymentCommand(emptyMap(), applicationDeploymentRef, auroraConfigRef)

        val deploymentSpec = createDeploymentSpec(defaultAuroraConfig(), DEFAULT_AID)
        val deployId = "12e456"

        val deployResult = AuroraDeployResult(
            command = command,
            auroraDeploymentSpecInternal = deploymentSpec,
            deployId = deployId,
            reason = "DONE"
        )

        val deployer = Deployer("Test Testesen", "test0test.no")

        val fileName = "test/$deployId.json"

        every {
            bitbucketService.uploadFile("ao", "auroradeploymenttags", fileName, "DEPLOY/utv-foo/bar", any())
        } returns "Success"

        val response = service.markRelease(listOf(deployResult), deployer)

        verify(exactly = 1) {
            bitbucketService.uploadFile("ao", "auroradeploymenttags", fileName, "DEPLOY/utv-foo/bar", any())
        }
        assertThat(response.size).isEqualTo(1)
        assertThat(response.first().bitbucketStoreResult).isEqualTo("Success")
    }

    @Test
    fun `Should mark failed release`() {

        val bitbucketService = mockk<BitbucketService>()
        val service = DeployLogService(bitbucketService, jsonMapper(), "ao", "auroradeploymenttags")

        val auroraConfigRef = AuroraConfigRef("test", "master", "123")
        val applicationDeploymentRef = ApplicationDeploymentRef("foo", "bar")
        val command = ApplicationDeploymentCommand(emptyMap(), applicationDeploymentRef, auroraConfigRef)

        val deploymentSpec = createDeploymentSpec(defaultAuroraConfig(), DEFAULT_AID)
        val deployId = "12e456"

        val deployResult = AuroraDeployResult(
            command = command,
            auroraDeploymentSpecInternal = deploymentSpec,
            deployId = deployId,
            reason = "DONE"
        )

        val deployer = Deployer("Test Testesen", "test0test.no")

        val error = RuntimeException("Some really bad stuff happend")
        val fileName = "test/$deployId.json"
        every {
            bitbucketService.uploadFile("ao", "auroradeploymenttags", fileName, "DEPLOY/utv-foo/bar", any())
        } throws error

        val response = service.markRelease(listOf(deployResult), deployer)

        assertThat(response.size).isEqualTo(1)
        val answer = response.first()

        assertThat(answer.bitbucketStoreResult).isEqualTo("Some really bad stuff happend")
        assertThat(answer.reason).isEqualTo("DONE Failed to store deploy result.")
        assertThat(answer.deployId).isEqualTo("failed")
    }
}
