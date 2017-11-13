package no.skatteetaten.aurora.boober.controller

import static org.springframework.http.MediaType.APPLICATION_JSON
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

import org.junit.Rule
import org.springframework.restdocs.JUnitRestDocumentation
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.setup.MockMvcBuilders

import com.fasterxml.jackson.databind.JsonNode

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.controller.internal.ErrorHandler
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigFileControllerV1
import no.skatteetaten.aurora.boober.model.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.service.DeployBundleService
import spock.lang.Specification

class AuroraConfigControllerTest extends Specification {

  @Rule
  JUnitRestDocumentation restDocumentation = new JUnitRestDocumentation('test/docs/generated-snippets')

  MockMvc mockMvc

  def deployBundleService = Mock(DeployBundleService)

  void setup() {
    def controller = new AuroraConfigFileControllerV1(deployBundleService)
    mockMvc = MockMvcBuilders.
        standaloneSetup(controller)
        .setControllerAdvice(new ErrorHandler())
        .apply(documentationConfiguration(this.restDocumentation))
        .build()
  }

  def affiliation = 'aos'
  def fileName = 'about.json'
  def payload = [
      version         : "LJLØ",
      validateVersions: true,
      content         : ""
  ]
  def auroraConfig = AbstractAuroraConfigTest.createAuroraConfig([(fileName): AbstractAuroraConfigTest.DEFAULT_ABOUT])

  def "A simple test that verifies that put payload is parsed correctly server side"() {

    given:
      payload.content = AbstractAuroraConfigTest.DEFAULT_ABOUT
      deployBundleService.updateAuroraConfigFile(affiliation, fileName,
          { JsonNode it -> it.get("affiliation").textValue() == affiliation }, payload.version, payload.validateVersions
      ) >> auroraConfig
    when:
      ResultActions result = mockMvc.perform(
          put("/v1/auroraconfigfile/$affiliation/$fileName").content(JsonOutput.toJson(payload)).
              contentType(APPLICATION_JSON))

    then:
      result.andExpect(status().isOk())
  }

  def "A simple test that verifies that patch payload is parsed correctly server side"() {

    given:
      payload.content = """[{
  "op": "replace",
  "path": "/version",
  "value": 3
}]"""

      deployBundleService.patchAuroraConfigFile(affiliation, fileName, payload.content, payload.version, payload.validateVersions) >>
          auroraConfig
    when:
      ResultActions result = mockMvc.perform(
          patch("/v1/auroraconfigfile/$affiliation/$fileName").content(JsonOutput.toJson(payload)).
              contentType(APPLICATION_JSON))

    then:
      result.andExpect(status().isOk())
  }
}
