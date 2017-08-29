package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentCoreMapperV1
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    EncryptionService,
    AuroraConfigService,
    Config
])
class AuroraConfigFieldTest extends Specification {

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

  def "Should generate correct config extractors"() {
    given:
      def aid = new ApplicationId("config", "console")
      def deployCommand = new DeployCommand(aid, [])
      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def mapper = new AuroraDeploymentCoreMapperV1(auroraConfig, deployCommand, [:])

    then:
      mapper.configHandlers.collect { it.path } == ["/config/foo", "/config/bar", "/config/1/bar", "/config/1/foo"]

  }
}


