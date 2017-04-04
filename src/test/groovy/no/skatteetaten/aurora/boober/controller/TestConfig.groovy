package no.skatteetaten.aurora.boober.controller

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import no.skatteetaten.aurora.boober.service.OpenShiftClient
import no.skatteetaten.aurora.boober.service.OpenShiftService
import spock.mock.DetachedMockFactory

@Configuration
class TestConfig {

  private DetachedMockFactory factory = new DetachedMockFactory()

  @Bean
  OpenShiftService openShiftService() {
    return factory.Stub(OpenShiftService, name: "openShiftService")
  }

  @Bean
  OpenShiftClient openShiftClient() {
    return factory.Stub(OpenShiftClient, name: "openShiftClient")
  }
}
