package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.TemplateType.deploy
import static no.skatteetaten.aurora.boober.service.openshift.OperationType.CREATE
import static no.skatteetaten.aurora.boober.service.openshift.OperationType.UPDATE

import org.junit.Before
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Shared
import spock.lang.Unroll

@DefaultOverride(auroraConfig = false)
class OpenShiftObjectGeneratorTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  OpenShiftObjectGenerator openShiftService

  @Autowired
  OpenShiftResourceClient openShiftResourceClient

  @Autowired
  DeployBundleService deployBundleService

  @Autowired
  DeployService deployService

  @Autowired
  ObjectMapper mapper

  @Shared
  def file = new ObjectMapper().convertValue([version: "1.0.4"], JsonNode.class)

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

        openShiftResourceClient.post("processedtemplate", _, null, _) >>
            new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)
      }
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, affiliation, additionalFile)
      deployBundleService.saveAuroraConfig(auroraConfig, false)

    expect:

      AuroraDeploymentSpec deploymentSpec = deployBundleService.createAuroraDeploymentSpec("aos", aid, overrides)
      def deployId = "123"

      List<JsonNode> generatedObjects = openShiftService.
          with { [generateProjectRequest(deploymentSpec)] + generateApplicationObjects(deploymentSpec, deployId) }

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

      env           | name            | templateFile      | overrides
      "booberdev"   | "console"       | null              | []
      "webseal"     | "sprocket"      | null              | []
      "booberdev"   | "sprocket"      | null              | []
      "booberdev"   | "tvinn"         | "atomhopper.json" | []
      "jenkins"     | "build"         | null              | []
      "booberdev"   | "reference-web" | null              | []
      "booberdev"   | "build"         | null              | []
      "booberdev"   | "aos-simple"    | null              | booberDevAosSimpleOverrides
      "secrettest"  | "aos-simple"    | null              | []
      "release"     | "aos-simple"    | null              | []
      "release"     | "build"         | null              | []
      "mounts"      | "aos-simple"    | null              | []
      "secretmount" | "aos-simple"    | null              | []
  }

  def docker = "docker/foo/bar:baz"

  def "Testing deploy OpenShift resources"() {

    given:
      def aid = new ApplicationId(env, name)
      def templateType = deploy
      def response = createOpenShiftResponse(kind, operation, prev, curr)
      def resultFiles = AuroraConfigHelperKt.getDeployResultFiles(aid)

    expect:
      JsonNode result = deployService.generateRedeployResource(templateType, name, docker, [response])

      def key = getKey(result)
      compareJson(resultFiles[key], result)

    where:
      env         | name       | operation | kind               | prev | curr
      "booberdev" | "sprocket" | CREATE    | "imagestream"      | 1    | 1
      "booberdev" | "tvinn"    | UPDATE    | "deploymentconfig" | 1    | 2
  }

  def compareJson(JsonNode jsonNode, JsonNode target) {
    assert JsonOutput.prettyPrint(target.toString()) == JsonOutput.prettyPrint(jsonNode.toString())
    true
  }

  def getKey(JsonNode it) {
    def kind = it.get("kind").asText().toLowerCase()
    def metadata = it.get("metadata")
    def name

    if (metadata == null) {
      name = it.get("name").asText().toLowerCase()
    } else {
      name = metadata.get("name").asText().toLowerCase()
    }

    return "$kind/$name" as String
  }
}
