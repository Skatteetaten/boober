package no.skatteetaten.aurora.boober.service

import groovy.json.JsonSlurper
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

class OpenShiftObjectGeneratorServiceTest extends AbstractOpenShiftObjectGeneratorTest {

  OpenShiftObjectGenerator objectGenerator = createObjectGenerator()

  def "toxiproxy services must be created if toxiproxy is enabled in deployment spec for java"() {

    given: "default deployment spec with toxiproxy version 2.1.3 enabled"
      AuroraDeploymentSpec deploymentSpec = specJavaWithToxiproxy()
      Map<String, String> serviceLabels = [:]

    when: "service object has been created"
      def svc = objectGenerator.generateService(deploymentSpec, serviceLabels)
      svc = new JsonSlurper().parseText(svc.toString()) // convert to groovy for easier navigation and validation

    then: "the svc must contain both http and admin ports"
      def ports = svc.spec.ports

      ports.find { it.name == "http" && it.port == 80 && it.targetPort == 8090 }
      ports.find { it.name == "management" && it.port == 8474 && it.targetPort == 8474 }
  }

}
