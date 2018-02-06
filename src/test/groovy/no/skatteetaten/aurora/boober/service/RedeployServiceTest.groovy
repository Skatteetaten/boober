package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import spock.lang.Specification

class RedeployServiceTest extends Specification {
  private def redeployService

  void setup() {
    redeployService = new RedeployService(Mock(OpenShiftClient), Mock(OpenShiftObjectGenerator))
  }

  def "Trigger redeploy"() {
    given:
      def redeployContext = Mock(RedeployContext) {
        generateRedeployResourceFromSpec() >> Mock(JsonNode)
      }

    when:
      redeployService.triggerRedeploy(redeployContext)

    then:
      noExceptionThrown()

  }
}
