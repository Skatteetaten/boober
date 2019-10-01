package no.skatteetaten.aurora.boober.unit

import no.skatteetaten.aurora.boober.utils.AbstractAuroraConfigTest

class DeployLogServiceTest : AbstractAuroraConfigTest() {

    /*
    // TODO: Fix
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
        assertThat(response.first().bitbucketStoreResult).isEqualTo("Success")
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
        assertThat(answer.deployId).isEqualTo("failed")
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


    private fun createDeployResult() = listOf(
        AuroraDeployResult(
                command = ApplicationDeploymentCommand(
                    overrideFiles = emptyMap(),
                    applicationDeploymentRef = ApplicationDeploymentRef("foo", "bar"),
                    auroraConfig = AuroraConfigRef("test", "master", "123")
                ),
                auroraDeploymentSpecInternal = null, //TOOD: fix
                deployId = deployId,
                reason = "DONE",
                deployCommand = cmd
        )
    )
     */
}
