package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.ClientType
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftApiUrls
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@TestPropertySource(properties = ["openshift.url = http://localhost"])
@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    OpenShiftClient,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
    Config,
    UserDetailsProvider
])
class OpenShiftClientCommandTest extends Specification {

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
  ObjectMapper mapper

  def setup() {
    userClient.getAuthorizationHeaders() >> new HttpHeaders()
  }

  def "Should create delete command for all resources with given deployId"() {
    given:
      def name = "aos-simple"
      def namespace = "booberdev"
      def deployId = "abc123"
      def baseUrl = "http://localhost"
      def aid = new ApplicationId(namespace, name)

      def responses = createResponsesFromResultFiles(aid)

      responses.each {
        def kind = it.key
        def queryString = "labelSelector=app%3D$name%2CbooberDeployId%2CbooberDeployId%21%3D$deployId"
        def apiUrl = OpenShiftApiUrls.getCollectionPathForResource(baseUrl, kind, namespace)
        def url = "$apiUrl?$queryString" as String

        userClient.get(url, _) >> ResponseEntity.ok(it.value)
      }

    when:
      def commands = openShiftClient.createOpenShiftDeleteCommands(name, namespace, deployId)

    then:
      ["BuildConfig", "DeploymentConfig", "ConfigMap", "ImageStream", "Route", "Service"].forEach {
        assert containsKind(it, commands)
      }
  }

  Map<String, JsonNode> createResponsesFromResultFiles(ApplicationId aid) {
    (Map<String, JsonNode>) AuroraConfigHelperKt.getResultFiles(aid).collectEntries {
      def responseBody = mapper.createObjectNode()
      def items = mapper.createArrayNode()

      def kind = it.key.split("/")[0]
      def kindList = it.value.get("kind").textValue() + "List"

      items.add(it.value)
      responseBody.put("kind", kindList)
      responseBody.set("items", items)

      [(kind): responseBody]
    }
  }

  Boolean containsKind(String kind, List<OpenshiftCommand> commands) {
    def found = commands.find {
      def payloadKind = it.payload.get("kind")
      if (payloadKind != null && payloadKind.textValue().equalsIgnoreCase(kind)) {
        true
      }
    }
    found != null
  }

}
