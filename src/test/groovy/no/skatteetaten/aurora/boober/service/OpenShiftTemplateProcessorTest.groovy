package no.skatteetaten.aurora.boober.service

import java.time.Duration

import org.apache.commons.text.StringSubstitutor
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraDeployEnvironment
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.Permission
import no.skatteetaten.aurora.boober.model.Permissions
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient

class OpenShiftTemplateProcessorTest extends AbstractSpec {

  ObjectMapper mapper = new ObjectMapper()
  def templateProcessor = new OpenShiftTemplateProcessor(
      Mock(UserDetailsProvider) { getAuthenticatedUser() >> new User('username', 'token', '', []) },
      Mock(OpenShiftResourceClient),
      mapper)
  String template = loadResource("jenkins-cluster-persistent-2.0.json")
  JsonNode templateJson = mapper.readValue(template, JsonNode)

  def "Should validate when all required parameters are set"() {

    when:
      def params = [AFFILIATION: "aos", VOLUME_CAPACITY: "512Mi"]
      templateProcessor.validateTemplateParameters(templateJson, params)

    then:
      noExceptionThrown()
  }

  def "Should validate when all required parameters with no defaults are set"() {

    when:
      def params = [AFFILIATION: "aos"]
      templateProcessor.validateTemplateParameters(templateJson, params)

    then:
      noExceptionThrown()
  }

  def "Throws exception when parameters are missing"() {

    when:
      def errors = templateProcessor.validateTemplateParameters(templateJson, [:])

    then:
      !errors.isEmpty()
      errors[0] == "Required template parameters [AFFILIATION] not set"
  }

  def "Throws exception when extra parameters are provided"() {

    when:
      def params = [AFFILIATION: "aos", VOLUME_CAPACITY: "512Mi", EXTRA: "SHOULD NOT BE SET"]
      def errors = templateProcessor.validateTemplateParameters(templateJson, params)

    then:
      !errors.isEmpty()
  }

  def "Generate template object with labels"() {
    given:
      def openshiftClient = Mock(OpenShiftResourceClient)
      templateProcessor = new OpenShiftTemplateProcessor(templateProcessor.userDetailsProvider, openshiftClient, mapper)

    when:
      def objects = templateProcessor.generateObjects(templateJson as ObjectNode, [:], createEmptyDeploymentSpec(), "1", 0)

    then:
      1 * openshiftClient.post(_ as String, _ as ObjectNode) >> {
        String url, ObjectNode payload ->
          assertLabels(payload['labels'] as ObjectNode)
          ResponseEntity.ok(payload)
     }
      objects.size() > 0
  }

  private static void assertLabels(ObjectNode labels) {
    assert labels['affiliation'] != null
    assert labels['template'].asText() == 'jenkins-cluster-persistent'
    assert labels['app'] != null
    assert labels['updatedBy'].asText() == 'username'
    assert labels['updateInBoober'] == null
  }

  private static createEmptyDeploymentSpec() {
    new AuroraDeploymentSpecInternal(new ApplicationDeploymentRef('', ''), '', TemplateType.development, '',
        new AuroraDeploymentSpec([:], new StringSubstitutor()),
        '', '', new AuroraDeployEnvironment('paas', 'booberdev',
        new Permissions(new Permission(new HashSet<String>(), new HashSet<String>()),
            new Permission(new HashSet<String>(), new HashSet<String>())),
        Duration.ofMinutes(30)),
        null, null, null, null, null, null, null, "master",  null, [:], [])
  }
}
