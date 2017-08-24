package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.internal.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.openshift.UserDetailsTokenProvider
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
    UserDetailsTokenProvider
])
class OpenShiftClientApplyTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    @OpenShiftResourceClientConfig.ClientType(API_USER)
    OpenShiftResourceClient resourceClient() {

      factory.Mock(OpenShiftResourceClient)
    }

    @Bean
    @OpenShiftResourceClientConfig.ClientType(SERVICE_ACCOUNT)
    OpenShiftResourceClient resourceClientSA() {

      factory.Mock(OpenShiftResourceClient)
    }
  }

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  OpenShiftResourceClient resource

  @Autowired
  ObjectMapper mapper

  def "Should throw exception if unknown error occured"() {

    given:
      def prFile = this.getClass().getResource("/openshift-objects/project.json")

      def projectRequest = mapper.readTree(prFile)

    when:

      resource.get("projectrequest", "foobar", "foobar") >> {
        throw new OpenShiftException("Does not exist", new HttpClientErrorException(HttpStatus.SERVICE_UNAVAILABLE))
      }
      openShiftClient.prepare("foobar", projectRequest)

    then:
      thrown(OpenShiftException)
  }

  def "Should create project if it does not exist"() {

    given:
      def prFile = this.getClass().getResource("/openshift-objects/project.json")

      def projectRequest = mapper.readTree(prFile)

    when:

      resource.get("projectrequest", "foobar", "foobar") >> null

      resource.post("projectrequest", "foobar", "foobar", projectRequest) >>
          new ResponseEntity(projectRequest, HttpStatus.OK)
      def result = openShiftClient.prepare("foobar", projectRequest)

    then:

      result.operationType == OperationType.CREATE
  }

  def "Should not update project if it does exist"() {

    given:
      def prFile = this.getClass().getResource("/openshift-objects/project.json")

      def projectRequest = mapper.readTree(prFile)

    when:

      resource.get("projectrequest", "foobar", "foobar") >>
          new ResponseEntity(projectRequest, HttpStatus.OK)

      def result = openShiftClient.prepare("foobar", projectRequest)

    then:

      result == null
  }

  @Unroll
  def "Should update #type"() {

    given:
      def oldResource = mapper.readTree(this.getClass().getResource("/openshift-objects/${type}.json"))
      def newResource = mapper.readTree(this.getClass().getResource("/openshift-objects/$type-new.json"))


      resource.get(type, "referanse", "foobar") >>
          new ResponseEntity(oldResource, HttpStatus.OK)

      resource.put(type, "referanse", "foobar", _) >>
          new ResponseEntity(oldResource, HttpStatus.OK)

    expect:
      def result = openShiftClient.prepare("foobar", newResource)
      result.operationType == OperationType.UPDATE
      fields.each { result.payload.at(it) == result.previous.at(it) }



    where:
      type               | fields
      "service"          | ["/metadata/resourceVersion", "/spec/clusterIP"]
      "deploymentconfig" | ["/metadata/resourceVersion", "/spec/template/spec/containers/0/image"]
      "buildconfig"      | ["/metadata/resourceVersion", "/spec/triggers"]
      "configmap"        | ["/metadata/resourceVersion"]

  }

}
