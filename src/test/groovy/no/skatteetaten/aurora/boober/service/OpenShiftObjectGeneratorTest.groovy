package no.skatteetaten.aurora.boober.service

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode

import groovy.json.JsonOutput
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.internal.ApplicationDeploymentGenerator
import no.skatteetaten.aurora.boober.service.internal.Provisions
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaInstance
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultResults
import spock.lang.Shared
import spock.lang.Unroll

class OpenShiftObjectGeneratorTest extends AbstractOpenShiftObjectGeneratorTest {

  OpenShiftObjectGenerator objectGenerator = createObjectGenerator("hero")

  @Shared
  def file = '''{ "version": "1.0.4"}'''

  @Shared
  def booberDevAosSimpleOverrides = [new AuroraConfigFile("booberdev/aos-simple.json", file, true, false)]

  def "ensure that message exist in application deployment object"() {
    given:
      def auroraConfigJson = defaultAuroraConfig()
      auroraConfigJson["utv/aos-simple.json"] = '''{ "message": "Aurora <3" }'''

    when:
      def spec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
      def auroraConfigRef = new AuroraConfigRef("test", "master", "123")
      def command = new ApplicationDeploymentCommand([:], DEFAULT_AID, auroraConfigRef)
      def provisions = new Provisions([])
      def applicationDeployment = ApplicationDeploymentGenerator.generate(spec, "123", command, "luke", provisions)
    then:
      applicationDeployment.spec.message == "Aurora <3"
  }

  def "ensure that database exist in application deployment object"() {
    given:
      def auroraConfigJson = defaultAuroraConfig()

    when:
      def spec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
      def auroraConfigRef = new AuroraConfigRef("test", "master", "123")
      def command = new ApplicationDeploymentCommand([:], DEFAULT_AID, auroraConfigRef)
      def schema = new DbhSchema("123-456", "MANAGED", new DatabaseSchemaInstance(1234, null), "",
          ["name": "referanse"], [])
      def provisions = new Provisions([schema])
      def applicationDeployment = ApplicationDeploymentGenerator.generate(spec, "123", command, "luke", provisions)
    then:
      applicationDeployment.spec.databases.contains("123-456")
  }

  @Unroll
  def "should create openshift objects for #env/#name"() {

    given:
      def provisioningResult = new ProvisioningResult(null,
          new VaultResults([foo: ["latest.properties": "FOO=bar\nBAR=baz\n".bytes]]), null)

      def aid = new ApplicationDeploymentRef(env, name)
      def additionalFile = null
      if (templateFile != null) {

        additionalFile = "templates/$templateFile"
        def templateFileName = "/samples/processedtemplate/${aid.environment}/${aid.application}/$templateFile"
        def templateResult = this.getClass().getResource(templateFileName)
        JsonNode jsonResult = mapper.readTree(templateResult)

        openShiftResourceClient.post(_ as String, _ as JsonNode) >> {
          new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)
        }
      }
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, AFFILIATION, additionalFile)
      AuroraDeploymentSpecInternal deploymentSpec = AuroraDeploymentSpecService.
          createAuroraDeploymentSpecInternal(auroraConfig, aid, overrides)
      def ownerReference = new OwnerReferenceBuilder()
          .withApiVersion("skatteetaten.no/v1")
          .withKind("ApplicationDeployment")
          .withName(deploymentSpec.name)
          .withUid("123-123")
          .build()

    when:

      List<JsonNode> generatedObjects = objectGenerator.
          with {
            [generateProjectRequest(deploymentSpec.environment)] +
                generateApplicationObjects(DEPLOY_ID, deploymentSpec, provisioningResult, ownerReference)
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
      "booberdev"   | "aos-simple"    | null              | booberDevAosSimpleOverrides
      "booberdev"   | "tvinn"         | "atomhopper.json" | []
      "booberdev"   | "reference"     | null              | []
      "booberdev"   | "console"       | null              | []
      "webseal"     | "sprocket"      | null              | []
      "booberdev"   | "sprocket"      | null              | []
      "booberdev"   | "reference-web" | null              | []
      "secrettest"  | "aos-simple"    | null              | []
      "release"     | "aos-simple"    | null              | []
      "mounts"      | "aos-simple"    | null              | []
      "secretmount" | "aos-simple"    | null              | []

  }

  def "generate rolebinding should include serviceaccount "() {

    given:
      def aid = new ApplicationDeploymentRef("booberdev", "console")
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, AFFILIATION, null)

    when:
      AuroraDeploymentSpecInternal deploymentSpec = AuroraDeploymentSpecService.
          createAuroraDeploymentSpecInternal(auroraConfig, aid, [])
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

  def "generate rolebinding view should split groups"() {

    given:
      def aid = new ApplicationDeploymentRef("booberdev", "console")
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, AFFILIATION, null)

    when:
      AuroraDeploymentSpecInternal deploymentSpec = AuroraDeploymentSpecService.
          createAuroraDeploymentSpecInternal(auroraConfig, aid, [])
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
