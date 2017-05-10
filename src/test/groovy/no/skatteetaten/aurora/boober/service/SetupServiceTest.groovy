package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getQaEbsUsersSampleFilesForEnv

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.internal.AuroraConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient

import no.skatteetaten.aurora.boober.utils.SampleFilesCollector
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration,
    GitService,
    AuroraDeploymentConfigService,
    OpenShiftService,
    EncryptionService,
    Config])
class SetupServiceTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    UserDetailsProvider userDetailsProvider() {

      factory.Mock(UserDetailsProvider)
    }

    @Bean
    OpenShiftClient openshiftClient() {
      factory.Mock(OpenShiftClient)
    }

    @Bean
    GitService gitService() {
      factory.Mock(GitService)
    }
  }

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  UserDetailsProvider userDetailsProvider
  @Autowired
  AuroraDeploymentConfigService auroraDeploymentConfigService

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  private static AuroraConfig createAuroraConfig(String envName = SampleFilesCollector.ENV_NAME,
      Map<String, String> secrets = [:]) {

    Map<String, JsonNode> files = getQaEbsUsersSampleFilesForEnv(envName)
    new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, secrets)
  }

  def "Should create aurora dc for application"() {

    given:
      userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> true

      AuroraConfig auroraConfig = createAuroraConfig()

    when:
      def result = auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [aid], [], false)

    then:
      result != null
  }

  def "Should get error if user is not valid"() {

    given:
      userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> false

      AuroraConfig auroraConfig = createAuroraConfig()

    when:
      auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [aid], [], true)

    then:
      AuroraConfigException e = thrown()
      e.errors.size() == 1
      e.errors[0].messages[0] == "The following users are not valid=foo"
  }

  def "Should get error if group is not valid"() {

    given:
      userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
      openShiftClient.isValidGroup(_) >> false
      openShiftClient.isValidUser(_) >> true

      AuroraConfig auroraConfig = createAuroraConfig()

    when:
      auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [aid], [], true)

    then:
      AuroraConfigException e = thrown()
      e.errors.size() == 1
      e.errors[0].messages[0] == "The following groups are not valid=APP_PaaS_drift, APP_PaaS_utv"
  }

  def "Should collect secrets"() {

    given:
      userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> true

      def envName = "secrettest"
      AuroraConfig auroraConfig = createAuroraConfig(envName, ["/tmp/foo/latest.properties": "FOO=BAR"])

    when:

      def result = auroraDeploymentConfigService.
          createAuroraDcs(auroraConfig, [new ApplicationId(envName, APP_NAME)], [], false)

    then:
      result[0].secrets.containsKey("latest.properties")
  }

  def "Should get error if we want secrets but there are none "() {

    given:
      userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> true

      def envName = "secrettest"
      AuroraConfig auroraConfig = createAuroraConfig(envName)

    when:
      auroraDeploymentConfigService.createAuroraDcs(auroraConfig, [new ApplicationId(envName, APP_NAME)], [], false)


    then:
      thrown(AuroraConfigException)
  }
}
