package no.skatteetaten.aurora.boober.service

import org.junit.Before
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.controller.internal.DeployParams
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import spock.mock.DetachedMockFactory

class OpenShiftObjectGeneratorTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  OpenShiftObjectGenerator openShiftService

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  OpenShiftResourceClient openShiftResourceClient

  @Autowired
  DeployBundleService deployBundleService

  @Autowired
  DeployService deployService

  @Autowired
  ObjectMapper mapper

  @Autowired
  VaultFacade vaultFacade

  def setup() {
    userDetailsProvider.authenticatedUser >> new User("hero", "token", "Test User")
    openShiftClient.isValidGroup(_) >> true
    openShiftClient.isValidUser(_) >> true
    openShiftClient.hasUserAccess(_, _) >> true
  }

  @Shared
  def file = new ObjectMapper().convertValue([managementPath: ":8080/test"], JsonNode.class)

  @Shared
  def booberDevAosSimpleOverrides = [new AuroraConfigFile("booberdev/aos-simple.json", file, true, null)]

  def affiliation = "aos"

  @Before
  def "Setup git"() {
    GitServiceHelperKt.createInitRepo(affiliation)
  }

  @Unroll
  def "should create openshift objects for #env/#name"() {

    given:
      def vault = new AuroraSecretVault("foo", ["latest.properties": "Rk9PPWJhcgpCQVI9YmF6Cg=="], null, [:])
      vaultFacade.save(affiliation, vault, false)

      def aid = new ApplicationId(env, name)
      def additionalFile = null
      if (templateFile != null) {

        additionalFile = "templates/$templateFile"
        def templateFileName = "/samples/processedtemplate/${aid.environment}/${aid.application}/$templateFile"
        def templateResult = this.getClass().getResource(templateFileName)
        JsonNode jsonResult = mapper.readTree(templateResult)

        openShiftResourceClient.post("processedtemplate", null, _, _) >>
            new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)
      }
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, affiliation, additionalFile)
      deployBundleService.saveAuroraConfig(auroraConfig, false)

    expect:

      def deployParams = new DeployParams([env], [name], overrides, false)
      def aac = deployService.dryRun("aos", deployParams)[0]

      def deployId = "123"

      List<JsonNode> generatedObjects = openShiftService.generateObjects(aac.auroraApplication, deployId)

      def resultFiles = AuroraConfigHelperKt.getResultFiles(aid)

      def keys = resultFiles.keySet()

      generatedObjects.forEach {
        def key = getKey(it)
        assert keys.contains(key)
        compareJson(resultFiles[key], it)
      }

      generatedObjects.collect { getKey(it) } as Set == resultFiles.keySet()

    when:

    where:
      env           | name         | templateFile      | overrides
      "jenkins"     | "build"      | null              | []
      "booberdev"   | "build"      | null              | []
      "booberdev"   | "console"    | null              | []
      "booberdev"   | "aos-simple" | null              | booberDevAosSimpleOverrides
      "booberdev"   | "tvinn"      | "atomhopper.json" | []
      "secrettest"  | "aos-simple" | null              | []
      "booberdev"   | "sprocket"   | null              | []
      "release"     | "aos-simple" | null              | []
      "release"     | "build"      | null              | []
      "mounts"      | "aos-simple" | null              | []
      "secretmount" | "aos-simple" | null              | []

  }

  def compareJson(JsonNode jsonNode, JsonNode target) {
    assert JsonOutput.prettyPrint(target.toString()) == JsonOutput.prettyPrint(jsonNode.toString())
    true
  }

  def getKey(JsonNode it) {
    def kind = it.get("kind").asText().toLowerCase()
    def name = it.get("metadata").get("name").asText().toLowerCase()

    return "$kind/$name" as String
  }

}
