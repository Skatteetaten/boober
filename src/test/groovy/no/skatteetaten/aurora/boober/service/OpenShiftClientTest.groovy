package no.skatteetaten.aurora.boober.service

import org.apache.commons.lang.builder.ReflectionToStringBuilder
import org.apache.commons.lang.builder.ToStringStyle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration, OpenShiftClient, OpenShiftService, Config])
class OpenShiftClientTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    UserDetailsProvider userDetailsProvider() {

      factory.Mock(UserDetailsProvider)
    }
  }

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  OpenShiftService openShiftService

  @Autowired
  UserDetailsProvider userDetailsProvider

  def auroraDc = TestDataKt.auroraDcDevelopment

  def "Smoke test"() {

    given:
      def token = "oc whoami -t".execute().text.trim()
      userDetailsProvider.getAuthenticatedUser() >> new User("test", token, "Test User")

      List<JsonNode> openShiftObjects = openShiftService.generateObjects(auroraDc)
      def project = openShiftObjects.find { it.get('kind').asText() == "ProjectRequest" }

      def resource = openShiftClient.createResourceClient(false, project, "aurora-boober-test")
    expect:
      def openShiftResponse = openShiftClient.apply("aurora-boober-test", project, resource)
      println ReflectionToStringBuilder.toString(openShiftResponse, ToStringStyle.MULTI_LINE_STYLE)
      true
  }
}
