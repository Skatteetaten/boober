package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecRendererKt.filterDefaultFields

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import spock.lang.Unroll

class AuroraDeploymentSpecRendererTest extends AbstractAuroraDeploymentSpecSpringTest {

  ObjectMapper mapper = new ObjectMapper()

  def auroraConfigJson = [
      "about.json"           : DEFAULT_ABOUT,
      "utv/about.json"       : DEFAULT_UTV_ABOUT,
      "webleveranse.json"    : WEB_LEVERANSE,
      "reference.json"       : REFERENCE,
      "utv/reference.json"   : '''{}''',
      "utv/webleveranse.json": '''{ "type": "development", "version": "1" }'''
  ]

  @Unroll
  def "Should create a Map of AuroraDeploymentSpec pointers for #env/#app with defaults #includeDefaults"() {
    given:
      def aid = ApplicationId.aid(env, app)
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)


      def fields=deploymentSpec.fields
      if (!includeDefaults) {
        fields = filterDefaultFields(fields)
      }
      def renderedJson = AuroraDeploymentSpecRendererKt.renderSpecAsJson(fields)
      def filename = getFilename(aid, includeDefaults)
      def expected = loadResource(filename)

    expect:
      def json = mapper.readTree(mapper.writeValueAsString(renderedJson))
      def expectedJson = mapper.readTree(expected)
      compareJson(expectedJson, json)

    where:
      env   | app            | includeDefaults
      "utv" | "webleveranse" | false
      "utv" | "webleveranse" | true
      "utv" | "reference"    | true
      "utv" | "reference"    | false
  }

  @Unroll
  def "Should render formatted json-like output for pointers for #env/#app with defaults #includeDefaults"() {
    given:
      def aid = ApplicationId.aid(env, app)
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)
      def renderedJson = AuroraDeploymentSpecRendererKt.
          renderJsonForAuroraDeploymentSpecPointers(deploymentSpec, includeDefaults)
      def filename = getFilename(aid, includeDefaults, true, "txt")
      def expected = loadResource(filename)

    expect:
      assert renderedJson == expected
      true

    where:
      env   | app            | includeDefaults
      "utv" | "webleveranse" | true
      "utv" | "reference"    | true
      "utv" | "webleveranse" | false
      "utv" | "reference"    | false
  }

  def getFilename(ApplicationId aid, boolean includeDefaults, boolean formatted = false, String type = "json") {
    String defaultSuffix = includeDefaults ? "-withDefaults" : ""
    String formattedText = formatted ? "-formatted" : ""

    return "${aid.application}${formattedText}${defaultSuffix}.${type}"

  }

  def compareJson(JsonNode jsonNode, JsonNode target) {
    assert JsonOutput.prettyPrint(target.toString()) == JsonOutput.prettyPrint(jsonNode.toString())
    true
  }
}
