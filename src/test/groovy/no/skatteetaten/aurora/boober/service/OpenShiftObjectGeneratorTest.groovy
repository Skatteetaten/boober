package no.skatteetaten.aurora.boober.service

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode

import groovy.json.JsonOutput
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultResults
import spock.lang.Shared
import spock.lang.Unroll

class OpenShiftObjectGeneratorTest extends AbstractOpenShiftObjectGeneratorTest {

  OpenShiftObjectGenerator objectGenerator = createObjectGenerator("hero")

  @Shared
  def file = '''{ "version": "1.0.4"}'''

  @Shared
  def booberDevAosSimpleOverrides = [new AuroraConfigFile("booberdev/aos-simple.json", file, true)]

  @Unroll
  def "should create openshift objects for #env/#name"() {

    given:
      def provisioningResult = new ProvisioningResult(null,
          new VaultResults([foo: ["latest.properties": "FOO=bar\nBAR=baz\n".bytes]]), null)

      def aid = new ApplicationId(env, name)
      def additionalFile = null
      if (templateFile != null) {

        additionalFile = "templates/$templateFile"
        def templateFileName = "/samples/processedtemplate/${aid.environment}/${aid.application}/$templateFile"
        def templateResult = this.getClass().getResource(templateFileName)
        JsonNode jsonResult = mapper.readTree(templateResult)

        openShiftResourceClient.post("processedtemplate", _, null, _) >> {
          new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)
        }
      }
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, AFFILIATION, additionalFile)
      AuroraDeploymentSpec deploymentSpec = AuroraDeploymentSpecService.
          createAuroraDeploymentSpec(auroraConfig, aid, overrides, "http://skap")

    when:
      List<JsonNode> generatedObjects = objectGenerator.
          with {
            [generateProjectRequest(deploymentSpec.environment)] +
                generateApplicationObjects(DEPLOY_ID, deploymentSpec, provisioningResult)
          }

    then:
      def resultFiles = AuroraConfigHelperKt.getResultFiles(aid)

      def keys = resultFiles.keySet()

      generatedObjects.forEach {
        def key = getKey(it)
        assert keys.contains(key)
        compareJson("/samples/result/${aid.environment}/${aid.application} $key", resultFiles[key], it)
      }

      generatedObjects.collect { getKey(it) } as Set == resultFiles.keySet()


    where:

      env           | name            | templateFile      | overrides
      "booberdev"   | "tvinn"         | "atomhopper.json" | []
      "booberdev"   | "reference"     | null              | []
      "booberdev"   | "console"       | null              | []
      "webseal"     | "sprocket"      | null              | []
      "booberdev"   | "sprocket"      | null              | []
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
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, AFFILIATION, null)

    when:
      AuroraDeploymentSpec deploymentSpec = AuroraDeploymentSpecService.
          createAuroraDeploymentSpec(auroraConfig, aid, [], "http://skap")
      def rolebindings = objectGenerator.generateRolebindings(deploymentSpec.environment.permissions)

    then:
      def adminRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "admin" }
      adminRolebinding != null

      getArray(adminRolebinding, "/userNames") == ["system:serviceaccount:paas:jenkinsbuilder"]
      getArray(adminRolebinding, "/groupNames") == ["APP_PaaS_utv", "APP_PaaS_drift"]

    and:
      def viewRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "view" }
      viewRolebinding != null

    and:
      rolebindings.size() == 2
  }

  def "should not include skap config if skap is disabled"() {

    given:
      def aid = new ApplicationId("booberdev", "console")
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, AFFILIATION, null)

    when:
      AuroraDeploymentSpec deploymentSpec = AuroraDeploymentSpecService.
          createAuroraDeploymentSpec(auroraConfig, aid, [], null)

    then:
      deploymentSpec

  }

  def "generate rolebinding view should split groups"() {

    given:
      def aid = new ApplicationId("booberdev", "console")
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, AFFILIATION, null)

    when:
      AuroraDeploymentSpec deploymentSpec = AuroraDeploymentSpecService.
          createAuroraDeploymentSpec(auroraConfig, aid, [], "http://skap")
      def rolebindings = objectGenerator.generateRolebindings(deploymentSpec.environment.permissions)

    then:
      def adminRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "admin" }
      getArray(adminRolebinding, "/groupNames") == ["APP_PaaS_utv", "APP_PaaS_drift"]

    and:
      def viewRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "view" }
      viewRolebinding != null

    and:
      rolebindings.size() == 2
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
