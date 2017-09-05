package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

import no.skatteetaten.aurora.boober.controller.internal.DeployParams
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException

class AuroraDeploymentConfigDeployServiceTest extends AbstractMockedOpenShiftSpecification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  @Autowired
  ObjectMapper mapper

  @Autowired
  DeployService service

  def "Should return error when name is not valid DNS952 label"() {

    given:
      def overrideFile = mapper.convertValue(["name": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environment}/${aid.application}.json", overrideFile, true, null)]
    when:
      service.dryRun("aos", new DeployParams([aid.environment], [aid.application], overrides, false))

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].messages[0].field.path == '/name'
  }

  def "Should return error when there are unmapped paths"() {

    given:
      def overrideFile = mapper.convertValue(["foo": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environment}/${aid.application}.json", overrideFile, true, null)]
    when:
      service.dryRun("aos", new DeployParams([aid.environment], [aid.application], overrides, false))

    then:
      def e = thrown(AuroraConfigException)
      def error = e.errors[0]
      def validationError = error.messages[0]
      error.application == aid.application
      error.environment == aid.environment
      validationError.fileName == "${aid.environment}/${aid.application}.json.override"
      validationError.message == "/foo is not a valid config field pointer"

  }

  def "Should fail due to missing config file"() {

    given:
      Map<String, JsonNode> files = AuroraConfigHelperKt.getSampleFiles(aid)
      files.remove("${APP_NAME}.json" as String)
      def auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false, null) }, "aos")

    when:
      auroraConfig.getFilesForApplication(aid)

    then:
      thrown(IllegalArgumentException)
  }

  @DefaultOverride(auroraConfig = false)
  def "Should throw ValidationException due to missing required properties"() {

    given: "AuroraConfig without build properties"
      Map<String, JsonNode> files = AuroraConfigHelperKt.getSampleFiles(aid)
      (files.get("aos-simple.json") as ObjectNode).remove("version")
      (files.get("booberdev/aos-simple.json") as ObjectNode).remove("version")
      AuroraConfig auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false, null) }, "aos")
      createRepoAndSaveFiles(auroraConfig)
    when:
      service.dryRun("aos", new DeployParams([aid.environment], [aid.application], [], false))

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].messages[0].message == "Version must be set"
  }

  def "Should get error if we want secrets but there are none "() {

    when:

      def json = mapper.convertValue(["secretVault": "notfound)"], JsonNode.class)
      def overrideAosFile = new AuroraConfigFile("aos-simple.json", json, true, null)
      service.dryRun("aos", new DeployParams([aid.environment], [aid.application], [overrideAosFile], false))

    then:
      thrown(AuroraConfigException)
  }

}

