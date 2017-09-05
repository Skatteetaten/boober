package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.service.internal.TagCommand
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    Config,
    DockerService,
    ObjectMapper,
])
class DockerServiceTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    @Primary
    RestTemplate restTemplate() {
      factory.Mock(RestTemplate)
    }

  }

  @Autowired
  RestTemplate httpClient

  @Autowired
  DockerService service

  @Autowired
  ObjectMapper mapper

  def "Should tag from one tag to another"() {
    given:
      def manifest = mapper.convertValue([manifest: "foo"], JsonNode.class)
      httpClient.exchange(_, _) >> new ResponseEntity<JsonNode>(manifest, HttpStatus.OK)

    when:
      ResponseEntity<JsonNode> response = service.tag(new TagCommand("foo/bar", "1.2.3", "latest", "registry"))

    then:
      response.statusCode.is2xxSuccessful()

  }

  def "Should not tag if we cannot find manifest"() {
    given:
      def manifest = mapper.convertValue([manifest: "foo"], JsonNode.class)
      httpClient.exchange(_, _) >> new ResponseEntity<JsonNode>(manifest, HttpStatus.BAD_REQUEST)

    when:
      ResponseEntity<JsonNode> response = service.tag(new TagCommand("foo/bar", "1.2.3", "latest", "registry"))


    then:
      response.statusCode.is4xxClientError()

  }
}
