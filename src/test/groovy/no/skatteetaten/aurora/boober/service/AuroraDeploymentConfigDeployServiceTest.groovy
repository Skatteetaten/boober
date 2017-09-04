package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.UserDetailsTokenProvider
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    EncryptionService,
    AuroraConfigService,
    OpenShiftResourceClient,
    Config,
    UserDetailsTokenProvider
])
class AuroraDeploymentConfigDeployServiceTest extends Specification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)
  final ApplicationId secretAId = new ApplicationId("secrettest", APP_NAME)

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    UserDetailsProvider userDetailsProvider() {

      factory.Stub(UserDetailsProvider)
    }

    @Bean
    OpenShiftClient openShiftClient() {
      factory.Mock(OpenShiftClient)
    }

    @Bean
    GitService gitService() {
      factory.Mock(GitService)
    }
  }

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  ObjectMapper mapper

  @Autowired
  AuroraConfigService auroraDeploymentConfigService

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
    openShiftClient.isValidUser(_) >> true
    openShiftClient.isValidGroup(_) >> true
  }

  def "Should return error when name is not valid DNS952 label"() {

    given:

      def overrideFile = mapper.convertValue(["name": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environment}/${aid.application}.json", overrideFile, true, null)]
      final DeployCommand deployCommand = new DeployCommand(aid, overrides)

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
    when:
      auroraDeploymentConfigService.createAuroraApplication(deployCommand, auroraConfig, [:])

    then:
      thrown(ApplicationConfigException)

  }

  def "Should return error when there are unmapped paths"() {

    given:
      def overrideFile = mapper.convertValue(["foo": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environment}/${aid.application}.json", overrideFile, true, null)]
      final DeployCommand deployCommand = new DeployCommand(aid, overrides)

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
    when:
      auroraDeploymentConfigService.createAuroraApplication(deployCommand, auroraConfig, [:])

    then:
      def e = thrown(ApplicationConfigException)
      def error = e.errors[0]
      e.message.contains('Config for application aos-simple in environment booberdev contains errors')
      error.fileName == "${aid.environment}/${aid.application}.json.override"
      error.message == "/foo is not a valid config field pointer"

  }

  def "Should create AuroraDC for Console"() {
    given:
      def consoleAid = new ApplicationId("booberdev", "console")
      def deployCommand = new DeployCommand(consoleAid)
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def auroraDc = auroraDeploymentConfigService.createAuroraApplication(deployCommand, auroraConfig, [:])

    then:
      auroraDc.deploy.prometheus.port == 8081
      auroraDc.deploy.webseal.host == "webseal"
  }

  def "Should create AuroraConfigFields with overrides"() {
    given:
      def overrideFile = mapper.convertValue(["type": "deploy", "cluster": "utv"], JsonNode.class)
      def overrides = [new AuroraConfigFile("booberdev/about.json", overrideFile, true, null)]

      final DeployCommand deployCommand = new DeployCommand(aid, overrides)
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def auroraDc = auroraDeploymentConfigService.createAuroraApplication(deployCommand, auroraConfig, [:])

      def fields = auroraDc.fields
    then:
      fields['cluster'].source == "booberdev/about.json.override"
      fields['cluster'].value.asText() == "utv"
      fields['type'].source == "booberdev/about.json.override"
      fields['type'].value.asText() == "deploy"
  }

  def "Should fail due to missing config file"() {

    given:
      def deployCommand = new DeployCommand(aid)
      Map<String, JsonNode> files = AuroraConfigHelperKt.getSampleFiles(aid)
      files.remove("${APP_NAME}.json" as String)
      def auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false, null) }, "aos")

    when:
      auroraConfig.getFilesForApplication(deployCommand)

    then:
      thrown(IllegalArgumentException)
  }

  def "Should successfully merge all config files"() {

    given:
      def deployCommand = new DeployCommand(aid)
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def auroraDc = auroraDeploymentConfigService.
              createAuroraApplication(deployCommand, auroraConfig, [:])

    then:
      with(auroraDc) {
        namespace == "aos-${ENV_NAME}"
        affiliation == "aos"
        name == APP_NAME
        cluster == "utv"
        deploy.replicas == 1
        type == TemplateType.development
        permissions.admin.groups.containsAll(["APP_PaaS_drift", "APP_PaaS_utv"])
      }
  }

  def "Should override name property in 'app'.json with name in override"() {

    given:
      def overrideFile = mapper.convertValue(["name": "awesome-app"], JsonNode.class)
      def overrides = [new AuroraConfigFile("booberdev/about.json", overrideFile, true, null)]

      final DeployCommand deployCommand = new DeployCommand(aid, overrides)
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def auroraDc = auroraDeploymentConfigService.
              createAuroraApplication(deployCommand, auroraConfig, [:])

    then:
      auroraDc.name == "awesome-app"
  }

  def "Should throw ValidationException due to missing required properties"() {

    given: "AuroraConfig without build properties"
      def deployCommand = new DeployCommand(aid)
      Map<String, JsonNode> files = AuroraConfigHelperKt.getSampleFiles(aid)
      (files.get("aos-simple.json") as ObjectNode).remove("version")
      (files.get("booberdev/aos-simple.json") as ObjectNode).remove("version")
      AuroraConfig auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false, null) }, "aos")

    when:
      auroraDeploymentConfigService.createAuroraApplication(deployCommand, auroraConfig, [:])

    then:
      def ex = thrown(ApplicationConfigException)
      ex.errors[0].message == "Version must be set"
  }

  def "Should create aurora dc for application"() {

    given:
      def deployCommand = new DeployCommand(aid)
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = auroraDeploymentConfigService.createAuroraApplications(auroraConfig, [deployCommand], [:])

    then:
      result != null
  }

  def "Should get error if we want secrets but there are none "() {

    given:
      def deployCommand = new DeployCommand(secretAId)
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      auroraDeploymentConfigService.createAuroraApplications(auroraConfig, [deployCommand], [:])

    then:
      thrown(AuroraConfigException)
  }

}

