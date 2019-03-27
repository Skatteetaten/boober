package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder

class BitbucketServiceTest : ResourceLoader() {

    private val server = MockWebServer()
    private val baseUrl = server.url("/")
    private val restTemplate = RestTemplateBuilder().rootUri(baseUrl.toString()).build()

    val service = BitbucketService(
        mapper = jsonMapper(),
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

        /*
        TODO: Her føler jeg at vi mister noe i testen.
        def url = "https://git.aurora.skead.no/rest/api/1.0/projects/ao/repos/auroradeploymenttags/browse/filename.json"
        mockServer.expect(requestTo(url))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header(HttpHeaders.CONTENT_TYPE, startsWith("multipart/form-data;charset=UTF-8;boundary=")))
            .andExpect(content().string(StringContains.containsString("name="message"")))
            .andExpect(content().string(StringContains.containsString("name="content"")))

            .andRespond(withSuccess("""true""", TEXT_PLAIN))
            */
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

        //def url = "https://git.aurora.skead.no/rest/api/1.0/projects/$project/repos?limit=1000"
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
