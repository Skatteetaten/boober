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
import no.skatteetaten.aurora.boober.model.EncryptedFileVault
import no.skatteetaten.aurora.boober.service.VaultService
import spock.lang.Specification

class VaultControllerTest extends Specification {

  @Rule
  JUnitRestDocumentation restDocumentation = new JUnitRestDocumentation('test/docs/generated-snippets')

  MockMvc mockMvc

  def vaultService = Mock(VaultService)

  def vaultCollectionName = 'aos'

  void setup() {
    def controller = new VaultControllerV1(vaultService)
    mockMvc = MockMvcBuilders.
        standaloneSetup(controller)
        .setControllerAdvice(new ErrorHandler())
        .apply(documentationConfiguration(this.restDocumentation))
        .build()
  }

  def "Simple test that verifies payload is correctly parsed on save"() {

    given:
      def payload = [name: 'testVault', secrets: [:], permissions: []]
      1 * vaultService.import(vaultCollectionName, 'testVault', [], [:]) >> EncryptedFileVault.createFromFolder(new File("."))

    when:
      ResultActions result = mockMvc.perform(
          put("/v1/vault/$vaultCollectionName").content(JsonOutput.toJson(payload)).
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
      1 * vaultService.createOrUpdateFileInVault(vaultCollectionName, vaultName, secretName, fileContents) >> EncryptedFileVault.createFromFolder(new File("."))

    when:
      ResultActions result = mockMvc.perform(
          put("/v1/vault/$vaultCollectionName/$vaultName/$secretName")
              .content(JsonOutput.toJson(payload))
              .contentType(APPLICATION_JSON))

    then:
      result.andExpect(status().isOk())
  }
}
