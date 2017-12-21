package no.skatteetaten.aurora.boober.controller

import static org.springframework.http.MediaType.APPLICATION_JSON
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

import org.junit.Rule
import org.springframework.restdocs.JUnitRestDocumentation
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.setup.MockMvcBuilders

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.controller.internal.ErrorHandler
import no.skatteetaten.aurora.boober.controller.v1.VaultControllerV1
import no.skatteetaten.aurora.boober.service.VaultService
import spock.lang.Specification

class VaultControllerTest extends Specification {

  @Rule
  JUnitRestDocumentation restDocumentation = new JUnitRestDocumentation('test/docs/generated-snippets')

  MockMvc mockMvc

  def vaultFacade = Mock(VaultService)

  def affiliation = 'aos'

  void setup() {
    def controller = new VaultControllerV1(vaultFacade)
    mockMvc = MockMvcBuilders.
        standaloneSetup(controller)
        .setControllerAdvice(new ErrorHandler())
        .apply(documentationConfiguration(this.restDocumentation))
        .build()
  }

  def "Simple test that verifies payload is correctly parsed on save"() {

    given:
      def payload = [vault: [name: 'testvault', secrets: [:], versions: [:], permissions: null], validateVersions: true]
      1 * vaultFacade.save(affiliation, _, payload.validateVersions)

    when:
      ResultActions result = mockMvc.perform(
          put("/v1/vault/$affiliation").content(JsonOutput.toJson(payload)).
              contentType(APPLICATION_JSON))

    then:
      result.andExpect(status().isOk())
  }

  def "Simple test that verifies payload is correctly parsed on update secret file"() {

    given:
      def vaultName = "some_vault"
      def secretName = "some_secret"
      def fileContents = 'SECRET_PASS=asdlfkjaølfjaøf'
      def payload = [contents: fileContents, validateVersions: false]
      1 * vaultFacade.createOrUpdateFileInVault(affiliation, vaultName, secretName, fileContents)

    when:
      ResultActions result = mockMvc.perform(
          put("/v1/vault/$affiliation/$vaultName/secret/$secretName")
              .content(JsonOutput.toJson(payload))
              .contentType(APPLICATION_JSON))

    then:
      result.andExpect(status().isOk())
  }
}
