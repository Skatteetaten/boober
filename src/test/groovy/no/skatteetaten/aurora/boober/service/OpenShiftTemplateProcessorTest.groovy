package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient

class OpenShiftTemplateProcessorTest extends AbstractSpec {

  ObjectMapper mapper = new ObjectMapper()
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
      def errors = templateProcessor.validateTemplateParameters(templateJson, [:])

    then:
      !errors.isEmpty()
  }

  def "Throws exception when extra parameters are provided"() {

    when:
      def params = [AFFILIATION: "aos", VOLUME_CAPACITY: "512Mi", EXTRA: "SHOULD NOT BE SET"]
      def errors = templateProcessor.validateTemplateParameters(templateJson, params)

    then:
      !errors.isEmpty()
  }
}
