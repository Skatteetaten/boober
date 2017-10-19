package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import spock.lang.Unroll

class AuroraDeploymentSpecRendererTest extends AbstractAuroraDeploymentSpecTest {

  ObjectMapper mapper = new ObjectMapper()

  def auroraConfigJson = [
      "about.json"           : DEFAULT_ABOUT,
      "utv/about.json"       : DEFAULT_UTV_ABOUT,
      "webleveranse.json"    : WEB_LEVERANSE,
      "utv/webleveranse.json": '''{ "type": "development", "version": "1" }'''
  ]

  @Unroll
  def "Should render a json representation of AuroraDeploymentSpec for #env/#app"() {
    given:
      def aid = ApplicationId.aid(env, app)
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)
      def renderedJson = AuroraDeploymentSpecRendererKt.createMapForAuroraDeploymentSpecPointers(deploymentSpec)
      def resultFiles = AuroraConfigHelperKt.getRendererResultFiles(aid)

    expect:
      def json = mapper.readTree(mapper.writeValueAsString(renderedJson))
      def result = resultFiles["${app}.json"]
      compareJson(result, json)

    where:
      env   | app
      "utv" | "webleveranse"
  }

  def compareJson(JsonNode jsonNode, JsonNode target) {
    assert JsonOutput.prettyPrint(target.toString()) == JsonOutput.prettyPrint(jsonNode.toString())
    true
  }
}
