package no.skatteetaten.aurora.boober.unit

import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.service.BitbucketRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.BitbucketService
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import okhttp3.mockwebserver.MockWebServer

class BitbucketServiceTest {

    private val server = MockWebServer()
    private val baseUrl = server.url("/")
    private val restTemplate = RestTemplateBuilder().rootUri(baseUrl.toString()).build()

    val service = BitbucketService(
        restTemplateWrapper = BitbucketRestTemplateWrapper(restTemplate)
    )

    val project = "ao"
    val repo = "auroradeploymenttags"

    @Test
    fun `Verify upload file`() {

        server.execute("true") {
            val response = service.uploadFile(project, repo, "filename.json", "message", "foobar")
            assertThat(response).isEqualTo("true")
        }
    }

    @Test
    fun `Verify get files`() {

        server.execute(
            """{
            "size": 1,
        "limit": 25,
        "isLastPage": true,
        "values": [ "foo", "bar", "baz" ],
        "start": 0 }"""
        ) {
            val response = service.getFiles(project, repo, "foobar")
            assertThat(response).isEqualTo(listOf("foo", "bar", "baz"))
        }
    }

    @Test
    fun `Verify get file`() {

        // def url = "https://git.aurora.skead.no/projects/ao/repos/auroradeploymenttags/raw/foobar.json"
        server.execute("fooobar") {
            val response = service.getFile(project, repo, "foobar.json")
            assertThat(response).isEqualTo("fooobar")
        }
    }

    @Test
    fun `Verify get repo names`() {

        // def url = "https://git.aurora.skead.no/rest/api/1.0/projects/$project/repos?limit=1000"
        server.execute(
            """{
            "values" : [
    { "slug" : "foo"},
    { "slug" : "bar"}
    ]}"""
        ) {

            val response = service.getRepoNames(project)
            assertThat(response).isEqualTo(listOf("foo", "bar"))
        }
    }
}
