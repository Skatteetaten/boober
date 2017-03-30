package no.skatteetaten.aurora.boober.service

import org.apache.commons.lang.builder.ReflectionToStringBuilder
import org.apache.commons.lang.builder.ToStringStyle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

import com.fasterxml.jackson.databind.JsonNode

import spock.lang.Specification

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
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
      def project = openShiftObjects.find { it.get('kind').asText() == "Project" }
    expect:
      def openShiftResponse = openShiftClient.save("aurora-boober-test", project, token)
      println ReflectionToStringBuilder.toString(openShiftResponse, ToStringStyle.MULTI_LINE_STYLE)
      true
  }
}
