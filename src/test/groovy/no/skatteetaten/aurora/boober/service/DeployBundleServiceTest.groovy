package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

import no.skatteetaten.aurora.boober.mapper.AuroraConfigException
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt

class DeployBundleServiceTest extends AbstractMockedOpenShiftSpecification {

  public static final String ENV_NAME = "secrettest"
  public static final String APP_NAME = "aos-simple"
  def aid = new ApplicationId(ENV_NAME, APP_NAME)
  def affiliation = "aos"

  @Autowired
  GitService gitService

  private AuroraConfig getAuroraConfigFromGit(String affiliation, boolean decryptSecrets) {

    def git = gitService.checkoutRepository(affiliation)
//    def files = auroraConfigGitService.getAllAuroraConfigFiles(git)
//    def auroraConfig = deployBundleService.createAuroraConfigFromFiles(files.collectEntries { }, "aos")
    gitService.closeRepository(git)
return AuroraConfig.fromFolder(gitService.checkoutPath + "/" + affiliation)
//    return auroraConfig
  }

  def "Should return error when name is too long"() {

    given:
      def overrideFile = mapper.
          convertValue(["name": "this-is-a-really-really-very-very-long-name-that-is-not-alloweed-over-40-char"],
              JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environment}/${aid.application}.json", overrideFile, true)]
    when:
      deployBundleService.createAuroraDeploymentSpec("aos", aid, overrides)

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].field.handler.name == 'name'
  }

  def "Should return error when name is not valid DNS952 label"() {

    given:
      def overrideFile = mapper.convertValue(["name": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environment}/${aid.application}.json", overrideFile, true)]
    when:
      deployBundleService.createAuroraDeploymentSpec("aos", aid, overrides)

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].field.handler.name == 'name'
  }

  // The next two tests should probably be covered without having to go through DeployBundleService.
/*

  def "Should return error when there are unmapped paths"() {

    given:
      def overrideFile = mapper.convertValue(["foo": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environment}/${aid.application}.json", overrideFile, true)]
    when:
      deployBundleService.createAuroraDeploymentSpec("aos", aid, overrides)

    then:
      def e = thrown(AuroraConfigException)
      def error = e.errors[0]
      error.field.source.configName == "${aid.environment}/${aid.application}.json.override"
      error.message == "/foo is not a valid config field pointer"
  }

  @DefaultOverride(auroraConfig = false)
  def "Should throw ValidationException due to missing required properties"() {

    given:
      aid = new ApplicationId("booberdev", "aos-simple")
      Map<String, JsonNode> files = AuroraConfigHelperKt.getSampleFiles(aid)
      (files.get("aos-simple.json") as ObjectNode).remove("version")
      (files.get("$aid.environment/aos-simple.json" as String) as ObjectNode).remove("version")
      AuroraConfig auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, "aos")

      gitService.deleteFiles(auroraConfig.affiliation)
      GitServiceHelperKt.createInitRepo(auroraConfig.affiliation)


    when:
      deployBundleService.saveAuroraConfig(auroraConfig, false)

    then:
      def ex = thrown(MultiApplicationValidationException)
      ex.errors[0].throwable.message == "Config for application aos-simple in environment booberdev contains errors. Version must be set as string, Version must be set."
  }
*/
}
