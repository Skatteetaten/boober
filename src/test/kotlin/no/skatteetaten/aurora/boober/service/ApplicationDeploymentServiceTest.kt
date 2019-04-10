package no.skatteetaten.aurora.boober.service

import assertk.assert
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.v1.ApplicationRef
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.openshift.token.TokenProvider
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.setJsonFileAsBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder

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

    private val applicationRef = ApplicationRef("release", "aos-simple")
    private val applicationRefs = listOf(applicationRef, applicationRef)

    @Test
    fun `delete AppplicationDeployments given two ApplicationDeploymentRef return success`() {
        val response =
            MockResponse().setJsonFileAsBody("no/skatteetaten/aurora/boober/service/ApplicationDeployment/ApplicationDeploymentDelete.json")

        val requests = server.execute(response, response) {
            val deleteResponse =
                applicationDeploymentDeleteService.executeDelete(applicationRefs)
            assert(deleteResponse.filter { it.success }.size).isEqualTo(2)
        }

        assert(requests.size).isEqualTo(2)
    }

    @Test
    fun `get ApplicationDeployments given two 404 responses return non existing message`() {
        val response = MockResponse()
            .setResponseCode(404)
            .setJsonFileAsBody("no/skatteetaten/aurora/boober/service/ApplicationDeployment/ApplicationDeploymentNotExists.json")

        val requests = server.execute(response, response) {
            val failureResponse = applicationDeploymentDeleteService.checkApplicationDeploymentsExists(applicationRefs)

            assert(failureResponse.filter { !it.exists }.size).isEqualTo(2)
        }
        assert(requests.size).isEqualTo(2)
    }
    // TODO: introduce test for token when springcleaning is in master
    /*fun Assert<RecordedRequest>.containsBearerToken() = given { request ->
        request.headers[HttpHeaders.AUTHORIZATION]?.let {
            if (it.startsWith("Bearer")) return
        }
        expected("Authorization header to contain Bearer token")
    }*/

    @Test
    fun `get ApplicationDeployments given two ApplicationRefs return true for all`() {
        val response = MockResponse()
            .setJsonFileAsBody("no/skatteetaten/aurora/boober/service/ApplicationDeployment/ApplicationDeploymentExists.json")

        val requests = server.execute(response, response) {
            val getResponse = applicationDeploymentDeleteService.checkApplicationDeploymentsExists(applicationRefs)

            assert(getResponse.filter { it.exists }.size).isEqualTo(2)
        }

        assert(requests.size).isEqualTo(2)
    }

    @Test
    fun `get ApplicationDeployments given OpenShift Error return Response with failure`() {
        val response = MockResponse()
            .setResponseCode(403)
            .setJsonFileAsBody("no/skatteetaten/aurora/boober/service/ApplicationDeployment/ApplicationDeploymentOpenshiftError.json")

        val requests = server.execute(response, response) {
            val getResponse = applicationDeploymentDeleteService.checkApplicationDeploymentsExists(applicationRefs)

            assert(getResponse.filter { !it.success }.size).isEqualTo(2)
            assert(getResponse.filter { it.message.contains("OK") }).isEmpty()
        }

        assert(requests.size).isEqualTo(2)
    }
}
