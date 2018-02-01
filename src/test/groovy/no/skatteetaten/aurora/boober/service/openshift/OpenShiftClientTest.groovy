package no.skatteetaten.aurora.boober.service.openshift

import static org.springframework.http.HttpStatus.OK

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.service.AbstractSpec

class OpenShiftClientTest extends AbstractSpec {

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
      'rolebinding'      | "user"
      'route'            | "sa"
      'namespace'        | "sa"
      'service'          | "user"
      'deploymentconfig' | "user"
      'imagestream'      | "user"
  }

  def "Creates OpenShiftGroup indexes"() {

    given:
      def response = loadResource("response_groups.json")
      def userResponse = loadResource("response_users.json")
      serviceAccountClient.get("/oapi/v1/groups/", _, _) >> new ResponseEntity(new ObjectMapper().readValue(response, JsonNode), OK)
      serviceAccountClient.get("/oapi/v1/users", _, _) >> new ResponseEntity(new ObjectMapper().readValue(userResponse, JsonNode), OK)

    when:
      def openShiftGroups = openShiftClient.getGroups()

    then:
      openShiftGroups != null

      openShiftGroups.userGroups["k1111111"] == ['APP_PaaS_drift', 'APP_PaaS_utv', 'system:authenticated']
      openShiftGroups.userGroups["k3222222"] == ['APP_PROJ1_drift', 'system:authenticated']
      openShiftGroups.groupUsers['APP_PaaS_drift'] == ['k2222222', 'k1111111', 'k3333333', 'k4444444', 'y5555555', 'm6666666', 'm7777777', 'y8888888', 'y9999999']
      openShiftGroups.groupUsers['system:authenticated'] ==
                 ['mTestUser', 'k2222222', 'k1111111', 'k1222222', 'k3333333', 'k4444444', 'k3222222', 'k4222222', 'k7111111', 'y5555555', 'y8888888', 'y9999999', 'm2111111', 'm3111111', 'm4111111', 'm5111111', 'm5222222', 'm6222222', 'y6222222', 'm6111111', 'm6666666', 'm7777777', 'm8111111', 'x9111111']


  }
}
