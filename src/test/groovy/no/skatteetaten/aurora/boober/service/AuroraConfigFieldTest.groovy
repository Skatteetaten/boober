package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.mapper.v1.AuroraConfigMapperV1Deploy
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.DeployCommand
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    EncryptionService,
    AuroraConfigService,
    OpenShiftResourceClient,
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
      def mapper = auroraDeploymentConfigService.
          createMapper(deployCommand, auroraConfig, [:]) as AuroraConfigMapperV1Deploy

      def config = mapper.auroraConfigFields.getConfigMap(mapper.configHandlers)

    then:
      mapper.configHandlers.collect { it.path } == ["/config/foo", "/config/bar", "/config/1/bar", "/config/1/foo"]
      config ==
          ["1.properties": "bar=baz\\nfoo=baz", foo: "baaaar", bar: "bar", "latest.properties": "foo=baaaar\\nbar=bar"]
  }
}


