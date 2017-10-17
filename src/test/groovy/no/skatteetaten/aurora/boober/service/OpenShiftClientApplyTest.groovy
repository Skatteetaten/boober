package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.ClientType
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT

import java.nio.charset.Charset

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import spock.lang.Specification
import spock.lang.Unroll
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    OpenShiftClient,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
    Config,
    UserDetailsProvider,
])
class OpenShiftClientApplyTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    @ClientType(API_USER)
    @Primary
    OpenShiftResourceClient resourceClient() {

      factory.Mock(OpenShiftResourceClient)
    }

    @Bean
    @ClientType(SERVICE_ACCOUNT)
    OpenShiftResourceClient resourceClientSA() {

      factory.Mock(OpenShiftResourceClient)
    }
  }

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  @ClientType(API_USER)
  OpenShiftResourceClient userClient

  @Autowired
  @ClientType(SERVICE_ACCOUNT)
  OpenShiftResourceClient serviceAccountClient

  @Autowired
  ObjectMapper mapper

  def "Should throw exception if unknown error occurred"() {

    given:
      def prFile = this.getClass().getResource("/openshift-objects/project.json")

      def projectRequest = mapper.readTree(prFile)

      serviceAccountClient.get(_, _) >> new ResponseEntity<JsonNode>(HttpStatus.OK)
      userClient.get("project", "foobar", "foobar") >> {
        throw new OpenShiftException("Does not exist", new HttpClientErrorException(HttpStatus.SERVICE_UNAVAILABLE))
      }

    when:
      openShiftClient.createOpenShiftCommand("foobar", projectRequest)

    then:
      thrown(OpenShiftException)
  }

  def "Should create project if it does not exist"() {

    given:
      def prFile = this.getClass().getResource("/openshift-objects/project.json")

      def projectRequest = mapper.readTree(prFile)

    when:

      userClient.get("projectrequest", "foobar", "foobar") >> null

      userClient.post("projectrequest", "foobar", "foobar", projectRequest) >>
          new ResponseEntity(projectRequest, HttpStatus.OK)
      def result = openShiftClient.createOpenShiftCommand("foobar", projectRequest)

    then:

      result.operationType == OperationType.CREATE
  }



  @Unroll
  def "Should update #type"() {

    given:
      def oldResource = mapper.readTree(this.getClass().getResource("/openshift-objects/${type}.json"))
      def newResource = mapper.readTree(this.getClass().getResource("/openshift-objects/$type-new.json"))

      serviceAccountClient.get(_, _) >> new ResponseEntity<JsonNode>(HttpStatus.OK)

      userClient.get(type, "foobar", "referanse") >>
          new ResponseEntity(oldResource, HttpStatus.OK)

      userClient.put(type, "foobar", "referanse", _) >>
          new ResponseEntity(oldResource, HttpStatus.OK)

    expect:
      def result = openShiftClient.createOpenShiftCommand("foobar", newResource)
      result.operationType == OperationType.UPDATE
      fields.each { assert result.payload.at(it) == result.previous.at(it) }



    where:
      type               | fields
      "service"          | ["/metadata/resourceVersion", "/spec/clusterIP"]
      "deploymentconfig" | ["/metadata/resourceVersion", "/spec/template/spec/containers/0/image"]
      "buildconfig"      |
          ["/metadata/resourceVersion", "/spec/triggers/0/imageChange/lastTriggeredImageID", "/spec/triggers/1/imageChange/lastTriggeredImageID"]
      "configmap"        | ["/metadata/resourceVersion"]

  }

  def "Should record exception when command fails"() {
    given:
      JsonNode payload = mapper.convertValue([
          kind    : "service",
          metadata: [
              "name": "bar"
          ]
      ], JsonNode.class)

      def cmd = new OpenshiftCommand(OperationType.CREATE, payload)
      userClient.post("service", "foo", "bar", payload) >> {
        throw new OpenShiftException("Does not exist",
            new HttpClientErrorException(HttpStatus.SERVICE_UNAVAILABLE, "not available",
                '''{ "failed" : "true"}'''.bytes,
                Charset.defaultCharset()))
      }
    when:

      def result = openShiftClient.performOpenShiftCommand("foo", cmd)
    then:
      !result.success
      result.responseBody.get("failed").asText() == "true"

  }

  def "Should record exception when command fails with non json body"() {
    given:
      JsonNode payload = mapper.convertValue([
          kind    : "service",
          metadata: [
              "name": "bar"
          ]
      ], JsonNode.class)

      def cmd = new OpenshiftCommand(OperationType.CREATE, payload)
      userClient.post("service", "foo", "bar", payload) >> {
        throw new OpenShiftException("Does not exist",
            new HttpClientErrorException(HttpStatus.SERVICE_UNAVAILABLE, "not available", "failed".bytes,
                Charset.defaultCharset()))
      }
    when:

      def result = openShiftClient.performOpenShiftCommand("foo", cmd)
    then:
      !result.success
      result.responseBody.get("error").asText() == "failed"

  }

}
