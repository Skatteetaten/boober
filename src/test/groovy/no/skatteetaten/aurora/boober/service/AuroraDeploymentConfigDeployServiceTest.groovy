package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getQaEbsUsersSampleFiles
import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getSampleFiles

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.facade.AuroraConfigFacade
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfigDeploy
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    AuroraConfigFacade,
    EncryptionService,
    AuroraConfigService,
    OpenShiftResourceClient,
    Config
])
class AuroraDeploymentConfigDeployServiceTest extends Specification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"
  final DeployCommand aid = new DeployCommand(ENV_NAME, APP_NAME)
  final DeployCommand secretAId = new DeployCommand("secrettest", APP_NAME)

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
    openShiftClient.applyMany(_, _) >> []
  }

  def "Should return error when name is not valid DNS952 label"() {

    given:

      def overrideFile = mapper.convertValue(["name": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environmentName}/${aid.applicationName}.json", overrideFile, true)]
      final DeployCommand aid = new DeployCommand(ENV_NAME, APP_NAME, overrides)

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
    when:
      auroraDeploymentConfigService.createAuroraDc(aid, auroraConfig)

    then:
      thrown(ApplicationConfigException)

  }

  def "Should create AuroraDC for Console"() {
    given:
      def consoleAid = new DeployCommand("booberdev", "console")
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def auroraDc = auroraDeploymentConfigService.createAuroraDc(consoleAid, auroraConfig)

    then:
      auroraDc.prometheus.port == 8081
      auroraDc.webseal.path == "/webseal"
  }

  def "Should create AuroraConfigFields with overrides"() {
    given:

      def overrideFile = mapper.convertValue(["type": "deploy", "cluster": "utv"], JsonNode.class)
      def overrides = [new AuroraConfigFile("booberdev/about.json", overrideFile, true)]
      final DeployCommand aid = new DeployCommand(ENV_NAME, APP_NAME, overrides)
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def auroraDc = auroraDeploymentConfigService.createAuroraDc(aid, auroraConfig)

      def fields = auroraDc.fields
    then:
      fields['cluster'].source == "booberdev/about.json.override"
      fields['cluster'].value.asText() == "utv"
      fields['type'].source == "booberdev/about.json.override"
      fields['type'].value.asText() == "deploy"
  }

  def "Should fail due to missing config file"() {

    given:
      Map<String, JsonNode> files = getQaEbsUsersSampleFiles()
      files.remove("${APP_NAME}.json" as String)
      def auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, [:])

    when:
      auroraConfig.getFilesForApplication(aid)

    then:
      thrown(IllegalArgumentException)
  }

  def "Should successfully merge all config files"() {

    given:
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      AuroraDeploymentConfigDeploy auroraDc = auroraDeploymentConfigService.
          createAuroraDc(aid, auroraConfig) as AuroraDeploymentConfigDeploy

    then:
      with(auroraDc) {
        namespace == "aos-${ENV_NAME}"
        affiliation == "aos"
        name == APP_NAME
        cluster == "utv"
        replicas == 1
        type == TemplateType.development
        permissions.admin.groups.containsAll(["APP_PaaS_drift", "APP_PaaS_utv"])
      }
  }

  def "Should override name property in 'app'.json with name in override"() {

    given:
      def overrideFile = mapper.convertValue(["name": "awesome-app"], JsonNode.class)
      def overrides = [new AuroraConfigFile("booberdev/about.json", overrideFile, true)]

      final DeployCommand aid = new DeployCommand(ENV_NAME, APP_NAME, overrides)
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      AuroraDeploymentConfigDeploy auroraDc = auroraDeploymentConfigService.createAuroraDc(aid, auroraConfig)

    then:
      auroraDc.name == "awesome-app"
  }

  def "Should throw ValidationException due to missing required properties"() {

    given: "AuroraConfig without build properties"
      Map<String, JsonNode> files = getSampleFiles(aid)
      (files.get("verify-ebs-users.json") as ObjectNode).remove("version")
      (files.get("booberdev/verify-ebs-users.json") as ObjectNode).remove("version")
      AuroraConfig auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, [:])

    when:
      auroraDeploymentConfigService.createAuroraDc(aid, auroraConfig)

    then:
      def ex = thrown(ApplicationConfigException)
      ex.errors[0].message == "Version must be set"
  }

  def "Should create aurora dc for application"() {

    given:
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [aid])

    then:
      result != null
  }
  def "Should collect secrets"() {

    given:


      AuroraConfig auroraConfig = AuroraConfigHelperKt.
          createAuroraConfig(secretAId, ["/tmp/foo/latest.properties": "FOO=BAR"])

    when:

      def result = auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [secretAId])

    then:
      result[0].secrets.containsKey("latest.properties")
  }

  def "Should get error if we want secrets but there are none "() {

    given:
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [secretAId])

    then:
      thrown(AuroraConfigException)
  }
}

