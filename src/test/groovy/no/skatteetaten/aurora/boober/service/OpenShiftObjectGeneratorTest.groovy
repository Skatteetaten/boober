package no.skatteetaten.aurora.boober.service

import org.junit.Before
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
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
  AuroraConfigService auroraConfigService

  @Autowired
  DeployService deployService

  @Autowired
  ObjectMapper mapper

  @Value("\$")
  String checkoutFolder

  @Shared
  def file = new ObjectMapper().convertValue([version: "1.0.4"], JsonNode.class)

  @Shared
  def booberDevAosSimpleOverrides = [new AuroraConfigFile("booberdev/aos-simple.json", file, true)]

  def affiliation = "aos"

  @Before
  def "Setup git"() {
    GitServiceHelperKt.recreateCheckoutFolders()
    GitServiceHelperKt.recreateEmptyBareRepos(affiliation)
  }

  @Unroll
  def "should create openshift objects for #env/#name"() {

    given:
      def provisioningResult = new ProvisioningResult(null, new VaultResults([foo: ["latest.properties": "FOO=bar\nBAR=baz\n"]]))

      def aid = new ApplicationId(env, name)
      def additionalFile = null
      if (templateFile != null) {

        additionalFile = "templates/$templateFile"
        def templateFileName = "/samples/processedtemplate/${aid.environment}/${aid.application}/$templateFile"
        def templateResult = this.getClass().getResource(templateFileName)
        JsonNode jsonResult = mapper.readTree(templateResult)

        openShiftResourceClient.post("processedtemplate", _, null, _) >> {

          it[3]["labels"]["updatedBy"] == "hero"
          new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)
        }
      }
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, affiliation, additionalFile)
      auroraConfigService.save(auroraConfig)

    expect:

      AuroraDeploymentSpec deploymentSpec = deployBundleService.createAuroraDeploymentSpec("aos", aid, overrides)
      def deployId = "123"

      List<JsonNode> generatedObjects = openShiftService.
          with { [generateProjectRequest(deploymentSpec)] + generateApplicationObjects(deployId, deploymentSpec, provisioningResult) }

      def resultFiles = AuroraConfigHelperKt.getResultFiles(aid)

      def keys = resultFiles.keySet()

      generatedObjects.forEach {
        def key = getKey(it)
        assert keys.contains(key)
        compareJson("/samples/result/${aid.environment}/${aid.application} $key", resultFiles[key], it)
      }

      generatedObjects.collect { getKey(it) } as Set == resultFiles.keySet()

    when:

    where:

      env           | name            | templateFile      | overrides
      "booberdev"   | "reference"     | null              | []
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


  def "generate rolebinding should include serviceaccount "() {

    given:
      def aid = new ApplicationId("booberdev", "console")
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, affiliation, null)
      auroraConfigService.save(auroraConfig)

    when:
      AuroraDeploymentSpec deploymentSpec = deployBundleService.createAuroraDeploymentSpec("aos", aid, [])
      def rolebindings = openShiftService.generateRolebindings(deploymentSpec.permissions)

    then:
      rolebindings.size() == 1
      def rolebinding = rolebindings[0]
      getArray(rolebinding, "/userNames") == ["system:serviceaccount:paas:jenkinsbuilder"]
      getArray(rolebinding, "/groupNames") == ["APP_PaaS_utv", "APP_PaaS_drift"]

  }

  private List<String> getArray(JsonNode rolebinding, String path) {
    (rolebinding.at(path) as ArrayNode).toSet().collect { it.textValue() }
  }

  def compareJson(String file, JsonNode jsonNode, JsonNode target) {
    def expected = "$file\n" + JsonOutput.prettyPrint(target.toString())
    def actual = "$file\n" + JsonOutput.prettyPrint(jsonNode.toString())
    assert expected == actual
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
