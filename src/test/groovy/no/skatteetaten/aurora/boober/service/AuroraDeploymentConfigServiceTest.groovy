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
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.validation.AuroraConfigFieldHandlerKt
import no.skatteetaten.aurora.boober.service.validation.AuroraDeploymentConfigMapperV1
import no.skatteetaten.aurora.boober.utils.AuroraConfigHelper
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    AuroraConfigService,
    EncryptionService,
    AuroraDeploymentConfigService,
    OpenShiftResourceClient,
    AuroraDeploymentConfigMapperV1,
    Config
])
class AuroraDeploymentConfigServiceTest extends Specification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"
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
  AuroraDeploymentConfigService auroraDeploymentConfigService

  @Autowired
  AuroraDeploymentConfigMapperV1 mapperV1

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
  }

  def "Should return error when name is not valid DNS952 label"() {

    given:
      def overrideFile = mapper.convertValue(["name": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environmentName}/${aid.applicationName}.json", overrideFile, true)]
      AuroraConfig auroraConfig = AuroraConfigHelper.createAuroraConfig(aid)

    when:
      def auroraDc = auroraDeploymentConfigService.createAuroraDc(aid, auroraConfig, overrides)
      auroraDeploymentConfigService.validateAuroraDc(aid, auroraDc, false)
    then:
      thrown(ApplicationConfigException)

  }

  def "Should create AuroraDC for Console"() {
    given:
      def consoleAid = new ApplicationId("booberdev", "console")
      AuroraConfig auroraConfig = AuroraConfigHelper.createAuroraConfig(consoleAid)

    when:
      def auroraDc = auroraDeploymentConfigService.createAuroraDc(consoleAid, auroraConfig, [])

    then:
      auroraDc.prometheus.port == 8081
      auroraDc.webseal.path == "/webseal"
  }

  def "Should create AuroraConfigFields"() {
    given:
      AuroraConfig auroraConfig = AuroraConfigHelper.createAuroraConfig(aid)

    when:
      def fields = AuroraConfigFieldHandlerKt.extractFrom(mapperV1.extractors, auroraConfig.auroraConfigFiles)

    then:
      fields.size() == 21
  }

  def "Should create AuroraConfigFields with overrides"() {
    given:
      def overrideFile = mapper.convertValue(["type": "deploy", "cluster": "utv"], JsonNode.class)
      def overrides = [new AuroraConfigFile("booberdev/about.json", overrideFile, true)]
      AuroraConfig auroraConfig = AuroraConfigHelper.createAuroraConfig(aid)
      def auroraConfigFiles = auroraConfig.getFilesForApplication(aid, overrides)

    when:
      def fields = AuroraConfigFieldHandlerKt.extractFrom(mapperV1.extractors, auroraConfigFiles)

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
      auroraConfig.getFilesForApplication(aid, [])

    then:
      thrown(IllegalArgumentException)
  }

  def "Should successfully merge all config files"() {

    given:
      AuroraConfig auroraConfig = AuroraConfigHelper.createAuroraConfig(aid)

    when:
      AuroraDeploymentConfig auroraDc = auroraDeploymentConfigService.createAuroraDc(aid, auroraConfig, [])

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
      AuroraConfig auroraConfig = AuroraConfigHelper.createAuroraConfig(aid)

    when:
      AuroraDeploymentConfig auroraDc = auroraDeploymentConfigService.
          createAuroraDc(aid, auroraConfig, overrides)

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
      auroraDeploymentConfigService.createAuroraDc(aid, auroraConfig, [])

    then:
      def ex = thrown(ApplicationConfigException)
      ex.errors[0] == "Version must be set"
  }

  def "Should create aurora dc for application"() {

    given:
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> true

      AuroraConfig auroraConfig = AuroraConfigHelper.createAuroraConfig(aid)

    when:
      def result = auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [aid], [], false)

    then:
      result != null
  }

  def "Should get error if user is not valid"() {

    given:
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> false

      AuroraConfig auroraConfig = AuroraConfigHelper.createAuroraConfig(aid)

    when:
      auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [aid], [], true)

    then:
      def e = thrown(AuroraConfigException)
      e.errors.size() == 1
      e.errors[0].messages[0] == "The following users are not valid=foo"
  }

  def "Should get error if group is not valid"() {

    given:
      openShiftClient.isValidGroup(_) >> false
      openShiftClient.isValidUser(_) >> true

      AuroraConfig auroraConfig = AuroraConfigHelper.createAuroraConfig(aid)

    when:
      auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [aid], [], true)

    then:
      AuroraConfigException e = thrown()
      e.errors.size() == 1
      e.errors[0].messages[0] == "The following groups are not valid=APP_PaaS_drift, APP_PaaS_utv"
  }

  def "Should collect secrets"() {

    given:
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> true

      AuroraConfig auroraConfig = AuroraConfigHelper.createAuroraConfig(secretAId, ["/tmp/foo/latest.properties" : "FOO=BAR"])

    when:

      def result = auroraDeploymentConfigService.
          createAuroraDcs(auroraConfig, [secretAId], [], false)

    then:
      result[0].secrets.containsKey("latest.properties")
  }

  def "Should get error if we want secrets but there are none "() {

    given:
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> true

      AuroraConfig auroraConfig = AuroraConfigHelper.createAuroraConfig(secretAId)

    when:
      auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [secretAId], [], false)

    then:
      thrown(AuroraConfigException)
  }
}

