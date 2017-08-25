package no.skatteetaten.aurora.boober.controller

import org.springframework.context.annotation.Configuration

import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import spock.mock.DetachedMockFactory

@Configuration
class TestConfig {

  private DetachedMockFactory factory = new DetachedMockFactory()

  // @Bean
  OpenShiftClient openShiftClient() {
    return factory.Stub(OpenShiftClient, name: "openShiftClient")
  }
}
