package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import com.fasterxml.jackson.databind.node.TextNode
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigField
import no.skatteetaten.aurora.boober.model.AuroraConfigFieldSource
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.AuroraDeployCommand
import no.skatteetaten.aurora.boober.model.AuroraDeploymentContext
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeployResult
import no.skatteetaten.aurora.boober.service.BitbucketService
import no.skatteetaten.aurora.boober.service.DeployLogService
import no.skatteetaten.aurora.boober.service.DeployLogServiceException
import no.skatteetaten.aurora.boober.service.Deployer
import no.skatteetaten.aurora.boober.utils.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.utils.jsonMapper
import org.apache.commons.text.StringSubstitutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.nio.charset.Charset

class DeployLogServiceTest : AbstractAuroraConfigTest() {

    private val bitbucketService = mockk<BitbucketService>()
    private val deployId = "12e456"
    private val fileName = "test/$deployId.json"
    private val deployer = Deployer("Test Testesen", "test0test.no")
    private val service = DeployLogService(
            bitbucketService = bitbucketService,
            mapper = jsonMapper(),
            project = "ao",
            repo = "auroradeploymenttags"
    )

    @AfterEach
    fun tearDown() {
        clearMocks(bitbucketService)
    }

    @Test
    fun `Should mark release`() {
        every {
            bitbucketService.uploadFile("ao", "auroradeploymenttags", fileName, "DEPLOY/utv-foo/bar", any())
        } returns "Success"

        val response = service.markRelease(createDeployResult(), deployer)

        verify(exactly = 1) {
            bitbucketService.uploadFile("ao", "auroradeploymenttags", fileName, "DEPLOY/utv-foo/bar", any())
        }
        assertThat(response.size).isEqualTo(1)
        assertThat(actual = response.first().bitbucketStoreResult).isEqualTo("Success")
    }

    @Test
    fun `Should mark failed release`() {
        every {
            bitbucketService.uploadFile("ao", "auroradeploymenttags", fileName, "DEPLOY/utv-foo/bar", any())
        } throws RuntimeException("Some really bad stuff happened")

        val response = service.markRelease(createDeployResult(), deployer)

        assertThat(response.size).isEqualTo(1)
        val answer = response.first()

        assertThat(answer.bitbucketStoreResult).isEqualTo("Some really bad stuff happened")
        assertThat(answer.reason).isEqualTo("DONE Failed to store deploy result.")
        assertThat(answer.deployId).isEqualTo(deployId)
    }

    @Test
    fun `Find deploy result by id throws DeployLogServiceException when git file not found`() {
        every { bitbucketService.getFile(any(), any(), any()) } throws
                HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND,
                        "",
                        HttpHeaders(),
                        "404 ".toByteArray(),
                        Charset.defaultCharset()
                )

        assertThat {
            service.findDeployResultById(AuroraConfigRef("test", "master", "123"), "abc123")
        }.isNotNull().isFailure().isInstanceOf(DeployLogServiceException::class)
    }

    @Test
    fun `Find deploy result by id throws HttpClientErrorException given bad request`() {
        every { bitbucketService.getFile(any(), any(), any()) } throws
                HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST,
                        "",
                        HttpHeaders(),
                        "400".toByteArray(),
                        Charset.defaultCharset()
                )

        assertThat {
            service.findDeployResultById(AuroraConfigRef("test", "master", "123"), "abc123")
        }.isNotNull().isFailure().isInstanceOf(HttpClientErrorException::class)
    }

    private fun createDeployResult(): List<AuroraDeployResult> {
        val aboutFile = AuroraConfigFile("about.json", "{}")
        return listOf(
                AuroraDeployResult(
                        reason = "DONE",
                        deployCommand = AuroraDeployCommand(
                                headerResources = emptySet(),
                                resources = emptySet(),
                                user = User("hero", "token"),
                                deployId = deployId,
                                shouldDeploy = true,
                                context = AuroraDeploymentContext(
                                        spec = AuroraDeploymentSpec(
                                                fields = mapOf("cluster" to AuroraConfigField(sources = setOf(
                                                        AuroraConfigFieldSource(aboutFile, TextNode("utv"))))),
                                                replacer = StringSubstitutor()
                                        ),
                                        cmd = AuroraContextCommand(
                                                auroraConfig = AuroraConfig(
                                                        files = listOf(
                                                                aboutFile,
                                                                AuroraConfigFile("foo/about.json", "{}"),
                                                                AuroraConfigFile("bar.json", "{}"),
                                                                AuroraConfigFile("foo/bar.json", "{}")
                                                        ),
                                                        name = "paas",
                                                        version = "1"
                                                ),
                                                applicationDeploymentRef = ApplicationDeploymentRef("foo", "bar"),
                                                auroraConfigRef = AuroraConfigRef("test", "master", "123")
                                        ),
                                        features = emptyMap(),
                                        featureHandlers = emptyMap()

                                )
                        )
                )
        )
    }
}
