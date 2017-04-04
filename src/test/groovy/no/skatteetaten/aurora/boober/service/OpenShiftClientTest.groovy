package no.skatteetaten.aurora.boober.service

import org.apache.commons.lang.builder.ReflectionToStringBuilder
import org.apache.commons.lang.builder.ToStringStyle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.Configuration
import spock.lang.Specification

@SpringBootTest(classes = [Configuration, OpenShiftClient, OpenShiftService])
class OpenShiftClientTest extends Specification {

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  OpenShiftService openShiftService

  def auroraDc = TestDataKt.auroraDcDevelopment

  def A() {

    given:
      def token = "oc whoami -t".execute().text.trim()

      List<JsonNode> openShiftObjects = openShiftService.generateObjects(auroraDc, token)
      def project = openShiftObjects.find { it.get('kind').asText() == "ProjectRequest" }
    expect:
      def openShiftResponse = openShiftClient.apply("aurora-boober-test", project, token)
      println ReflectionToStringBuilder.toString(openShiftResponse, ToStringStyle.MULTI_LINE_STYLE)
      true
  }
}
