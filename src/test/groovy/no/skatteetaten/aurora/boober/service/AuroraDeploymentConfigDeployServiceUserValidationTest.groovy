package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.internal.DeployParams
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
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
class AuroraDeploymentConfigDeployServiceUserValidationTest extends Specification {

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
  }

  private void createRepoAndSaveFiles(String affiliation, AuroraConfig auroraConfig) {
    GitServiceHelperKt.createInitRepo(affiliation)
    deployBundleService.saveAuroraConfig(auroraConfig, false)
  }

  def "Should get error if user is not valid"() {

    given:

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
      createRepoAndSaveFiles("aos", auroraConfig)
      openShiftClient.isValidUser("foo") >> false
      openShiftClient.isValidGroup(_) >> true


    when:
      service.dryRun("aos", new DeployParams([aid.environment], [aid.application], [], false))

    then:
      def e = thrown(AuroraConfigException)
      e.errors.size() == 1
      e.errors[0].messages[0].message == "The following users are not valid=foo"
      e.errors[0].messages[0].field.source == "about.json"

  }

  def "Should get error if group is not valid"() {

    given:
      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
      createRepoAndSaveFiles("aos", auroraConfig)

      openShiftClient.isValidUser(_) >> true
      openShiftClient.isValidGroup(_) >> false
    when:

      service.dryRun("aos", new DeployParams([aid.environment], [aid.application], [], false))

    then:
      AuroraConfigException e = thrown()
      e.errors.size() == 1
      e.errors[0].messages[0].message == "The following groups are not valid=APP_PaaS_drift, APP_PaaS_utv"
      e.errors[0].messages[0].field.source == "about.json"
  }

}

