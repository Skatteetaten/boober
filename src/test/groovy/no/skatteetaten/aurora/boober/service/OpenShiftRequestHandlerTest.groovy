package no.skatteetaten.aurora.boober.service

import static org.springframework.http.HttpMethod.GET
import static org.springframework.http.MediaType.APPLICATION_JSON
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.test.web.client.MockRestServiceServer

import com.fasterxml.jackson.databind.JsonNode

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftRequestHandler

class OpenShiftRequestHandlerTest extends AbstractAuroraDeploymentSpecSpringTest {

  @Value('${openshift.url}')
  String openShiftUrl

  @Autowired
  MockRestServiceServer osClusterMock

  @Autowired
  OpenShiftRequestHandler requestHandler

  @Value('${openshift.url}/oapi/v1/namespaces/aos/deploymentconfigs/webleveranse')
  String resourceUrl

  def setup() {
    Logger root = (Logger)LoggerFactory.getLogger("no.skatteetaten")
    root.setLevel(Level.DEBUG)
  }

  def "Succeeds even if the request fails a couple of times"() {

    given:
      def resource = loadResource(OpenShiftClientCreateOpenShiftCommandTest.simpleName, "webleveranse.json")
      2.times { osClusterMock.expect(requestTo(resourceUrl)).andRespond(withBadRequest()) }
      osClusterMock.expect(requestTo(resourceUrl)).andRespond(withSuccess(resource, APPLICATION_JSON))

    when:
      ResponseEntity<JsonNode> entity = requestHandler.exchange(new RequestEntity<Object>(GET, new URI(resourceUrl)))

    then:
      JsonOutput.prettyPrint(entity.body.toString()) == JsonOutput.prettyPrint(resource)
  }

  def "Fails when exceeds retry attempts"() {

    given:
      def resourceUrl = "$openShiftUrl/oapi/v1/namespaces/aos/deploymentconfigs/webleveranse"
      3.times { osClusterMock.expect(requestTo(resourceUrl)).andRespond(withBadRequest()) }

    when:
      requestHandler.exchange(new RequestEntity<Object>(GET, new URI(resourceUrl)))

    then:
      thrown(OpenShiftException)
  }
}
