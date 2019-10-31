package no.skatteetaten.aurora.boober.facade

/*
TODO: move to facade
class ApplicationDeploymentServiceTest {

    private val server = MockWebServer()
    private val baseUrl = server.url("/")

    private val tokenProvider = mockk<TokenProvider>().apply {
        every { getToken() } returns "test-token"
    }

    private val restTemplate = RestTemplateBuilder().rootUri(baseUrl.toString()).build()
    private val openShiftRestTemplateWrapper = OpenShiftRestTemplateWrapper(restTemplate)
    private val openShiftResourceClient = OpenShiftResourceClient(tokenProvider, openShiftRestTemplateWrapper)

    private val openShiftClient =
        OpenShiftClient(openShiftResourceClient, openShiftResourceClient, jacksonObjectMapper())

    private val applicationDeploymentDeleteService = ApplicationDeploymentService(openShiftClient)

    private val applicationRef = ApplicationRef("utv", "simple")
    private val applicationRefs = listOf(applicationRef, applicationRef)

    @Test
    fun `delete AppplicationDeployments given two ApplicationDeploymentRef return success`() {
        val response =
            MockResponse().setJsonFileAsBody("no/skatteetaten/aurora/boober/service/ApplicationDeployment/ApplicationDeploymentDelete.json")

        val requests = server.execute(response, response) {
            val deleteResponse =
                applicationDeploymentDeleteService.executeDelete(applicationRefs)
            assertThat(deleteResponse.filter { it.success }.size).isEqualTo(2)
        }

        assertThat(requests.size).isEqualTo(2)
    }

    @Test
    fun `get ApplicationDeployments given two 404 responses return non existing message`() {
        val response = MockResponse()
            .setResponseCode(404)
            .setJsonFileAsBody("no/skatteetaten/aurora/boober/service/ApplicationDeployment/ApplicationDeploymentNotExists.json")

        val requests = server.execute(response, response) {
            val failureResponse = applicationDeploymentDeleteService.checkApplicationDeploymentsExists(applicationRefs)

            assertThat(failureResponse.filter { !it.exists }.size).isEqualTo(2)
        }
        assertThat(requests.size).isEqualTo(2)
    }

    @Test
    fun `get ApplicationDeployments given two ApplicationRefs return true for all`() {
        val response = MockResponse()
            .setJsonFileAsBody("no/skatteetaten/aurora/boober/service/ApplicationDeployment/ApplicationDeploymentExists.json")

        val requests = server.execute(response, response) {
            val getResponse = applicationDeploymentDeleteService.checkApplicationDeploymentsExists(applicationRefs)

            assertThat(getResponse.filter { it.exists }.size).isEqualTo(2)
        }

        assertThat(requests.size).isEqualTo(2)
    }

    @Test
    fun `get ApplicationDeployments given OpenShift Error return Response with failure`() {
        val response = MockResponse()
            .setResponseCode(400)
            .setJsonFileAsBody("no/skatteetaten/aurora/boober/service/ApplicationDeployment/ApplicationDeploymentOpenshiftError.json")

        val requests = server.execute(response, response) {
            val getResponse = applicationDeploymentDeleteService.checkApplicationDeploymentsExists(applicationRefs)

            assertThat(getResponse.filter { !it.success }.size).isEqualTo(2)
            assertThat(getResponse.filter { it.message.contains("OK") }).isEmpty()
        }

        assertThat(requests.size).isEqualTo(2)
    }
}
*/
