package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isNotEmpty
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.service.resourceprovisioning.AdminCredentials
import no.skatteetaten.aurora.boober.service.resourceprovisioning.FionaRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.resourceprovisioning.S3Access
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
    val fionaRestTemplate = FionaRestTemplateWrapper(restTemplate, baseUrl, 0)
    private var provisioner = S3Provisioner(fionaRestTemplate)

    val request = S3ProvisioningRequest(
        "bucketName",
        "path",
        jacksonObjectMapper().convertValue(AdminCredentials("adminSecretKey", "adminAccessKey")),
        "username",
        S3Access.WRITE
    )

    @Test
    fun `fails with unexpected response`() {

        server.expect(requestTo("$baseUrl/bucket/${request.bucketName}/path/${request.path}/userPolicy/${request.userName}"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        assertThat {
            provisioner.provision(request)
        }.isFailure()
    }

    @Test
    fun `smoke test for successful provisioning`() {
        @Language("JSON") val response = """{
            "serviceEndpoint": "http://minio:9000",
            "bucket": "default-bucket",
            "bucketRegion": "us-west-1",
            "secretKey": "some-key",
            "accessKey": "accesskey"
        }"""

        server.expect(requestTo("$baseUrl/bucket/${request.bucketName}/path/${request.path}/userPolicy/${request.userName}"))
            .andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
        val result = provisioner.provision(request)

        assertThat(result.objectPrefix).isNotEmpty()
        assertThat(result.request).isEqualTo(request)
    }
}
