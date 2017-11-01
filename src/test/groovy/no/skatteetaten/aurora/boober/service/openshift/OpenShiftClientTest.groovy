package no.skatteetaten.aurora.boober.service.openshift

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import spock.lang.Specification

class OpenShiftClientTest extends Specification {

  def userClient = Mock(OpenShiftResourceClient)

  def serviceAccountClient = Mock(OpenShiftResourceClient)

  def clients = ["sa": serviceAccountClient, "user": userClient]

  def mapper = new ObjectMapper()

  def openShiftClient = new OpenShiftClient("", userClient, serviceAccountClient, mapper)

  def "Uses correct resource client based on OpenShift kind"() {

    given:
      def name = 'does not matter'
      def mockedResource = """{ "kind": "$kind", "metadata": { "name": "$name" } }"""
      def command = new OpenshiftCommand(OperationType.CREATE, mapper.readValue(mockedResource, JsonNode))

      def expectedClient = clients[expectedClientName]
      def otherClient = clients.values().with { remove(expectedClient); it }.first()

    when:
      openShiftClient.performOpenShiftCommand("aos", command)

    then:
      1 * expectedClient.post(kind, 'aos', name, _ as JsonNode) >>
          new ResponseEntity(mapper.readValue("{}", JsonNode), HttpStatus.OK)

      0 * otherClient._

    where:
      kind               | expectedClientName
      'rolebinding'      | "sa"
      'route'            | "sa"
      'namespace'        | "sa"
      'service'          | "user"
      'deploymentconfig' | "user"
      'imagestream'      | "user"
  }
}
