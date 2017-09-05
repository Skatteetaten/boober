package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

import no.skatteetaten.aurora.boober.controller.internal.DeployParams
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.internal.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.internal.Error
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.openshift.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.service.openshift.UserDetailsTokenProvider
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    DeployService,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
    GitService,
    SecretVaultService,
    EncryptionService,
    DeployBundleService,
    VaultFacade,
    ObjectMapper,
    Config,
    OpenShiftResourceClientConfig,
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
    OpenShiftClient openshiftClient() {
      factory.Mock(OpenShiftClient)
    }

    @Bean
    ServiceAccountTokenProvider tokenProvider() {
      factory.Mock(ServiceAccountTokenProvider)
    }

    @Bean
    UserDetailsProvider userDetailsProvider() {
      factory.Mock(UserDetailsProvider)
    }

    @Bean
    DockerService dockerService() {
      factory.Mock(DockerService)
    }
  }

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  ObjectMapper mapper

  @Autowired
  DeployBundleService deployBundleService

  @Autowired
  DeployService service

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
    openShiftClient.isValidUser(_) >> true
    openShiftClient.isValidGroup(_) >> true
  }

  private void createRepoAndSaveFiles(String affiliation, AuroraConfig auroraConfig) {
    GitServiceHelperKt.createInitRepo(affiliation)
    deployBundleService.saveAuroraConfig(auroraConfig, false)
  }

  def "Should return error when name is not valid DNS952 label"() {

    given:
      def overrideFile = mapper.convertValue(["name": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environment}/${aid.application}.json", overrideFile, true, null)]

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
      createRepoAndSaveFiles("aos", auroraConfig)
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

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
      createRepoAndSaveFiles("aos", auroraConfig)
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


  def "Should throw ValidationException due to missing required properties"() {

    given: "AuroraConfig without build properties"
      Map<String, JsonNode> files = AuroraConfigHelperKt.getSampleFiles(aid)
      (files.get("aos-simple.json") as ObjectNode).remove("version")
      (files.get("booberdev/aos-simple.json") as ObjectNode).remove("version")
      AuroraConfig auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false, null) }, "aos")
      createRepoAndSaveFiles("aos", auroraConfig)
    when:
      service.dryRun("aos", new DeployParams([aid.environment], [aid.application], [], false))

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].messages[0].message == "Version must be set"
  }


  def "Should get error if we want secrets but there are none "() {

    given:
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
      createRepoAndSaveFiles("aos", auroraConfig)

    when:
      service.dryRun("aos", new DeployParams([aid.environment], [aid.application], [], false))

    then:
      thrown(AuroraConfigException)
  }

}

