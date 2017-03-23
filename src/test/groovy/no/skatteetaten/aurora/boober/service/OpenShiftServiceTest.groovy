package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getUtvReferanseSampleFiles

import org.apache.velocity.app.VelocityEngine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.Configuration
import spock.lang.Specification

class OpenShiftServiceTest extends Specification {

  Configuration configuration = new Configuration()
  VelocityEngine velocityEngine = configuration.velocity()
  ObjectMapper mapper = configuration.mapper()

  def openShiftService = new OpenshiftService("", velocityEngine)
  def configService = new ConfigService(mapper)

  def "Should execute"() {
    given:
      Map<String, JsonNode> files = getUtvReferanseSampleFiles()

    when:
      def booberResult = configService.createBooberResult("utv", "referanse", files)
      def openShiftResult = openShiftService.execute(booberResult, "herro")

    then:
      openShiftResult.openshiftObjects.size() == 4

  }

}
