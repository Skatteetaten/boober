package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.LoggingUtilsKt.setLogLevels
import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getQaEbsUsersSampleFiles
import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getQaEbsUsersSampleFilesForEnv

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraConfig
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration,
    AuroraConfigParserService,
    SetupService,
    OpenShiftService,
    Config])
class SetupServiceTest extends Specification {

  def setupSpec() {
    setLogLevels()
  }

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
  }

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  UserDetailsProvider userDetailsProvider
  @Autowired
  SetupService setupService

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def "Should create aurora dc for application"() {

    given:
      userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> true

      Map<String, Map<String, Object>> files = getQaEbsUsersSampleFiles()
      def auroraConfig = new AuroraConfig(files, [:])

    when:
      def result = setupService.createAuroraDcsForApplications(auroraConfig, [aid])

    then:
      result != null
  }

  def "Should get error if user is not valid"() {

    given:
      userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> false

      Map<String, Map<String, Object>> files = getQaEbsUsersSampleFiles()
      def auroraConfig = new AuroraConfig(files, [:])

    when:
      setupService.createAuroraDcsForApplications(auroraConfig, [aid])

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

      Map<String, Map<String, Object>> files = getQaEbsUsersSampleFiles()
      def auroraConfig = new AuroraConfig(files, [:])

    when:
      setupService.createAuroraDcsForApplications(auroraConfig, [aid])

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
      Map<String, Map<String, Object>> files = getQaEbsUsersSampleFilesForEnv(envName)
      def auroraConfig = new AuroraConfig(files, ["/tmp/foo/latest.properties": "FOO=BAR"])

    when:

      def result = setupService.createAuroraDcsForApplications(auroraConfig, [new ApplicationId(envName, APP_NAME)])

    then:
      result[0].secrets.containsKey("latest.properties")
  }

  def "Should get error if we want secrets but there are none "() {

    given:
      userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> true

      def envName = "secrettest"
      Map<String, Map<String, Object>> files = getQaEbsUsersSampleFilesForEnv(envName)
      def auroraConfig = new AuroraConfig(files, [:])

    when:

      setupService.createAuroraDcsForApplications(auroraConfig, [new ApplicationId(envName, APP_NAME)])


    then:
      AuroraConfigException e = thrown()
      e.errors.size() == 1
      e.errors[0].messages[0] == "No secret files with prefix /tmp/foo"
  }
}
