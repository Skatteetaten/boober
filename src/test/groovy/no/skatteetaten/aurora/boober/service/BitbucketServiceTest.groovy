package no.skatteetaten.aurora.boober.service

import static org.hamcrest.core.StringStartsWith.startsWith
import static org.springframework.http.MediaType.APPLICATION_JSON
import static org.springframework.http.MediaType.TEXT_PLAIN
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

import org.hamcrest.core.StringContains
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureWebClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.RestTemplate

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader

@AutoConfigureWebClient
@SpringBootTest(classes = [
    Configuration,
    SharedSecretReader,
    BitbucketService,
    BitbucketRestTemplateWrapper,
    SpringTestUtils.BitbucketMockRestServiceServiceInitializer]
)

class BitbucketServiceTest extends AbstractSpec {

  @Autowired
  MockRestServiceServer mockServer

  @Autowired
  BitbucketService service

  String project = "ao"
  String repo = "auroradeploymenttags"

  def "Verify upload file"() {

    given:
      def url = "https://git.aurora.skead.no/rest/api/1.0/projects/ao/repos/auroradeploymenttags/browse/filename.json"
      mockServer.expect(requestTo(url))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(header(HttpHeaders.CONTENT_TYPE, startsWith("multipart/form-data;boundary=")))
          .andExpect(content().string(StringContains.containsString('name="message"')))
          .andExpect(content().string(StringContains.containsString('name="content"')))

          .andRespond(withSuccess('''true''', TEXT_PLAIN))
    when:
      def response = service.uploadFile(project, repo, "filename.json", "message", "foobar")

    then:
      response == "true"
  }

  def "Verify get files"() {

    given:
      def url = "https://git.aurora.skead.no/rest/api/1.0/projects/ao/repos/auroradeploymenttags/files/foobar?limit=100000"
      mockServer.expect(requestTo(url))
          .andExpect(method(HttpMethod.GET))
          .andRespond(withSuccess('''{
    "size": 1,
    "limit": 25,
    "isLastPage": true,
    "values": [
        "foo", 
        "bar", 
        "baz"
    ],
    "start": 0
}''', APPLICATION_JSON))

    when:

      def response = service.getFiles(project, repo, "foobar")
    then:
      response == ["foo", "bar", "baz"]
  }

  def "Verify get file"() {

    given:

      def url = "https://git.aurora.skead.no/projects/ao/repos/auroradeploymenttags/raw/foobar.json"
      mockServer.expect(requestTo(url))
          .andExpect(method(HttpMethod.GET))
          .andRespond(withSuccess('''foooobar''', TEXT_PLAIN))

    when:

      def response = service.getFile(project, repo, "foobar.json")
    then:
      response == "foooobar"

  }

  def "Verify get repo names"() {
    given:

      def url = "https://git.aurora.skead.no/rest/api/1.0/projects/$project/repos?limit=1000"
      mockServer.expect(requestTo(url))
          .andExpect(method(HttpMethod.GET))
          .andRespond(withSuccess('''{ 
  "values" : [
    { "slug" : "foo"},
    { "slug" : "bar"}
]}''', APPLICATION_JSON))

    when:

      def response = service.getRepoNames(project)
    then:
      response == ["foo", "bar"]

  }

}
