package no.skatteetaten.aurora.boober.controller

import static org.springframework.http.MediaType.APPLICATION_JSON
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

import org.junit.Rule
import org.springframework.restdocs.JUnitRestDocumentation
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.setup.MockMvcBuilders

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import no.skatteetaten.aurora.boober.controller.internal.ErrorHandler
import no.skatteetaten.aurora.boober.controller.v1.VaultControllerV1
import no.skatteetaten.aurora.boober.service.FolderHelperKt
import no.skatteetaten.aurora.boober.service.vault.EncryptedFileVault
import no.skatteetaten.aurora.boober.service.vault.VaultService
import spock.lang.Specification

class VaultControllerTest extends Specification {

  @Rule
  JUnitRestDocumentation restDocumentation = new JUnitRestDocumentation('test/generated-snippets')

  MockMvc mockMvc

  def vaultService = Mock(VaultService)

  def vaultCollectionName = 'aos'

  def vaultName = "some_vault"

  def secretName = "some_secret"

  def fileContents = 'SECRET_PASS=asdlfkjaølfjaøf'

  void setup() {
    def controller = new VaultControllerV1(vaultService, new Responder(), false)
    mockMvc = MockMvcBuilders.
        standaloneSetup(controller)
        .setControllerAdvice(new ErrorHandler())
        .apply(documentationConfiguration(this.restDocumentation))
        .build()
  }

  def "Simple test that verifies payload is correctly parsed on save"() {

    given:
      def payload = [name: 'testVault', secrets: [:], permissions: []]
      1 * vaultService.import(vaultCollectionName, 'testVault', [], [:]) >>
          EncryptedFileVault.createFromFolder(new File("."))

    when:
      ResultActions result = mockMvc.perform(
          put("/v1/vault/$vaultCollectionName").content(JsonOutput.toJson(payload)).
              contentType(APPLICATION_JSON))

    then:
      result.andExpect(status().isOk())
  }

  def "Fails when provided secret file payload is not Base64 encoded"() {

    given:

      def payload = [contents: fileContents]

    when:
      ResultActions result = mockMvc.perform(
          put("/v1/vault/$vaultCollectionName/$vaultName/$secretName")
              .content(JsonOutput.toJson(payload))
              .contentType(APPLICATION_JSON))

    then:
      result.andExpect(status().isBadRequest())
  }

  def "Succeeds when provided secret file payload is Base64 encoded"() {

    given:
      def payload = [contents: fileContents.bytes.encodeBase64().toString()]
      1 * vaultService.createOrUpdateFileInVault(vaultCollectionName, vaultName, secretName, fileContents.bytes, null) >>
          EncryptedFileVault.createFromFolder(new File("."))

    when:
      ResultActions result = mockMvc.perform(
          put("/v1/vault/$vaultCollectionName/$vaultName/$secretName")
              .content(JsonOutput.toJson(payload))
              .contentType(APPLICATION_JSON))

    then:
      result.andExpect(status().isOk())
  }

  def "Get vault returns Base64 encoded content"() {

    given:
      EncryptedFileVault vault = createTestVault(vaultName, secretName, fileContents)
      1 * vaultService.findVault(vaultCollectionName, vaultName) >> vault

    when:
      ResultActions result = mockMvc.perform(get("/v1/vault/$vaultCollectionName/$vaultName"))

    then:
      result.andExpect(status().isOk())
      def resultBody = new JsonSlurper().parseText(result.andReturn().response.contentAsString)
      def vaultResponse = resultBody.items[0]
      vaultResponse.name == vaultName
      vaultResponse.secrets.size() == 1
      vaultResponse.secrets.some_secret == fileContents.bytes.encodeBase64().toString()
  }

  private EncryptedFileVault createTestVault(String vaultName, String secretName, String fileContents) {
    def folder = FolderHelperKt.recreateFolder("build/vaults/$vaultCollectionName/$vaultName")
    new File(folder, secretName).write(fileContents)
    def vault = EncryptedFileVault.createFromFolder(folder)
    vault
  }
}
