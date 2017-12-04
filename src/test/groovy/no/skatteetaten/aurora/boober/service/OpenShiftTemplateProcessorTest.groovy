package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient

class OpenShiftTemplateProcessorTest extends AbstractSpec {

  ObjectMapper mapper = new Configuration().mapper()
  def templateProcessor = new OpenShiftTemplateProcessor(Mock(UserDetailsProvider), Mock(OpenShiftResourceClient),
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
      templateProcessor.validateTemplateParameters(templateJson, [:])

    then:
      thrown(AuroraDeploymentSpecValidationException)
  }

  def "Throws exception when extra parameters are provided"() {

    when:
      def params = [AFFILIATION: "aos", VOLUME_CAPACITY: "512Mi", EXTRA: "SHOULD NOT BE SET"]
      templateProcessor.validateTemplateParameters(templateJson, params)

    then:
      thrown(AuroraDeploymentSpecValidationException)
  }
}
