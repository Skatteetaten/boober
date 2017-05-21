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
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Ignore
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration, OpenShiftResourceClient, OpenShiftClient, OpenShiftObjectGenerator, Config])
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
  OpenShiftObjectGenerator openShiftService

  @Autowired
  UserDetailsProvider userDetailsProvider

  def auroraDc = TestDataKt.auroraDcDevelopment

  @Ignore
  //denne kreer at vi har et cluster oppe. Trenger vi det nå?
  def "Smoke test"() {

    given:
      def token = "oc whoami -t".execute().text.trim()
      userDetailsProvider.getAuthenticatedUser() >> new User("test", token, "Test User")

      List<JsonNode> openShiftObjects = openShiftService.generateObjects(auroraDc)
      def project = openShiftObjects.find { it.get('kind').asText() == "ProjectRequest" }

    expect:
      def openShiftResponse = openShiftClient.apply("aurora-boober-test", project)
      println ReflectionToStringBuilder.toString(openShiftResponse, ToStringStyle.MULTI_LINE_STYLE)
      true
  }
}
