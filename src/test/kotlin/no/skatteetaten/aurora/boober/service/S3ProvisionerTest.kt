package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.isFailure
import assertk.assertions.isNotNull
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Provisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3ProvisioningRequest
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

@RestClientTest
@AutoConfigureWebClient(registerRestTemplate = true)
class S3ProvisionerTest @Autowired constructor(val server: MockRestServiceServer, restTemplate: RestTemplate) {
    val baseUrl = "http://fiona"
    private var provisioner = S3Provisioner(restTemplate, baseUrl)

    @Test
    fun `fails with unexpected response`() {

        server.expect(requestTo("$baseUrl/createuser")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        assertThat {
            provisioner.provision(S3ProvisioningRequest("aurora", "utv", "boober"))
        }.isFailure()
    }

    @Test
    fun `smoke test for successful provisioning`() {
        @Language("JSON") val response = """{
  "serviceEndpoint": "http://minio:9000",
  "bucket": "default-bucket",
  "bucketRegion": "us-west-1",
  "secretKey": "some-key"
}"""
        server.expect(requestTo("$baseUrl/createuser")).andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
        val result = provisioner.provision(S3ProvisioningRequest("aurora", "utv", "boober"))

        assertThat(result.accessKey).isNotNull()
    }
}
