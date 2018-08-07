package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.ObjectMapper

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.v1.AuroraRouteMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraVolumeMapperV1
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftGroups
import no.skatteetaten.aurora.boober.service.openshift.UserGroup
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@RestClientTest
@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    EncryptionService,
    Config,
    AuroraMetrics,
    SharedSecretReader
])
class AuroraConfigFieldTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    MeterRegistry meterRegistry() {
      Metrics.globalRegistry
    }

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

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User", [])
    openShiftClient.getGroups() >> new OpenShiftGroups([new UserGroup("foo", "APP_PaaS_utv")])
  }

  def "Should generate correct config extractors"() {
    given:
      def aid = new ApplicationId("config", "console")
      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

      def files = auroraConfig.getFilesForApplication(aid)
    when:
      def mapper = new AuroraVolumeMapperV1(files)

    then:
      mapper.configHandlers.collect { it.path } == ["/config/foo", "/config/bar", "/config/1/bar", "/config/1/foo"]
  }

  def "Should throw exception when annotation has wrong separator"() {
    given:
      def aid = new ApplicationId("route", "console")
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid)

    when:
      def routeMapper = new AuroraRouteMapperV1(auroraConfig.files, "console")
      def fields = AuroraConfigFields.create(routeMapper.handlers.toSet(), auroraConfig.files, [:])
      routeMapper.getRoute(fields)

    then:
      thrown AuroraDeploymentSpecValidationException
  }
}


