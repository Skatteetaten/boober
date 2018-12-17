package no.skatteetaten.aurora.boober.controller

import static org.springframework.http.MediaType.APPLICATION_JSON
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

import org.junit.Rule
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.restdocs.JUnitRestDocumentation
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.setup.MockMvcBuilders

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.controller.internal.ErrorHandler
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigControllerV1
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigResponder
import no.skatteetaten.aurora.boober.model.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraVersioningException
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraConfigService

class AuroraConfigControllerTest extends AbstractAuroraConfigTest {

  @Rule
  JUnitRestDocumentation restDocumentation = new JUnitRestDocumentation('test/docs/generated-snippets')

  MockMvc mockMvc

  def auroraConfigService = Mock(AuroraConfigService)

  def setup() {
    def controller = new AuroraConfigControllerV1(auroraConfigService, new AuroraConfigResponder())
    mockMvc = MockMvcBuilders.
        standaloneSetup(controller)
        .setControllerAdvice(new ErrorHandler())
        .apply(documentationConfiguration(this.restDocumentation))
        .build()
  }

  def auroraConfigName = 'aos'
  def ref = new AuroraConfigRef(auroraConfigName, "master", null)
  def fileName = 'about.json'
  def auroraConfig = createAuroraConfig([(fileName): DEFAULT_ABOUT])

  def "A simple test that verifies that put payload is parsed correctly server side"() {

    given:
      def payload = [content: DEFAULT_ABOUT]
      auroraConfigService.updateAuroraConfigFile(ref,fileName, _, _) >> auroraConfig
    when:
      ResultActions result = mockMvc.perform(
          put("/v1/auroraconfig/$auroraConfigName/$fileName").content(JsonOutput.toJson(payload)).
              contentType(APPLICATION_JSON))

    then:
      result.andExpect(status().isOk())
  }

  def "Provides eTag on single file requests"() {

    given:
      def payload = [content: DEFAULT_ABOUT]
      def auroraConfigFile = auroraConfig.findFile(fileName)
      auroraConfigService.findAuroraConfigFile(ref, fileName) >> auroraConfigFile

    when:
      ResultActions result = mockMvc.perform(get("/v1/auroraconfig/$auroraConfigName/$fileName"))

    then:
      result.andExpect(status().isOk())
          .andExpect(header().string("ETAG", "\"$auroraConfigFile.version\""))
  }

  def "Passes on IF-MATCH header"() {

    given:
      def fileToUpdate = DEFAULT_ABOUT
      def auroraConfigFile = new AuroraConfigFile("about.json", fileToUpdate,
          false, false)
      def eTag = "THIS_IS_NOT_THE_EXPECTED_ETAG"
      def payload = [content: modify(fileToUpdate, { route: true })]
      auroraConfigService.updateAuroraConfigFile(ref, fileName, _ as String, eTag) >> {
        throw new AuroraVersioningException(auroraConfig, auroraConfigFile, eTag)
      }

    when:
      ResultActions result = mockMvc.perform(
          put("/v1/auroraconfig/$auroraConfigName/$fileName").content(JsonOutput.toJson(payload))
              .contentType(APPLICATION_JSON)
              .header(HttpHeaders.IF_MATCH, "\"$eTag\"")
      )

    then:
      result.andExpect(status().is(HttpStatus.PRECONDITION_FAILED.value()))
  }

  def "A simple test that verifies that patch payload is parsed correctly server side"() {

    given:
      def payload = [content: """[{
  "op": "replace",
  "path": "/version",
  "value": 3
}]"""]

      auroraConfigService. patchAuroraConfigFile(ref, fileName, payload.content, payload.version) >> auroraConfig
    when:
      ResultActions result = mockMvc.perform(
          patch("/v1/auroraconfig/$auroraConfigName/$fileName").content(JsonOutput.toJson(payload)).
              contentType(APPLICATION_JSON))

    then:
      result.andExpect(status().isOk())
  }
}
