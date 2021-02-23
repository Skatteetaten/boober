package no.skatteetaten.aurora.boober.unit

import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureWebClient
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate
import assertk.assertThat
import assertk.assertions.isFailure
import assertk.assertions.isNotEmpty
import no.skatteetaten.aurora.boober.service.resourceprovisioning.FionaRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Access
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningRequest

@RestClientTest
@AutoConfigureWebClient(registerRestTemplate = true)
class S3ProvisionerTest @Autowired constructor(val server: MockRestServiceServer, restTemplate: RestTemplate) {
    val baseUrl = "http://fiona"
    val fionaRestTemplate = FionaRestTemplateWrapper(restTemplate, baseUrl, 0)
    private var provisioner = S3Provisioner(fionaRestTemplate)

    val request = S3ProvisioningRequest(
        "bucketName",
        "path",
        "username",
        listOf(S3Access.WRITE)
    )

    @Test
    fun `fails with unexpected response`() {

        server.expect(requestTo("$baseUrl/bucket/${request.bucketName}/path/${request.path}/userpolicies/${request.userName}"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        assertThat {
            provisioner.provision(request)
        }.isFailure()
    }

    @Test
    fun `smoke test for successful provisioning`() {
        @Language("JSON") val response = """{
            "accessKey": "accesskey",
            "secretKey": "some-key",
            "host": "http://fiona"
        }"""

        server.expect(requestTo("$baseUrl/buckets/${request.bucketName}/paths/${request.path}/userpolicies/"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
        val result = provisioner.provision(request)

        assertThat(result.serviceEndpoint).isNotEmpty()
    }
}
