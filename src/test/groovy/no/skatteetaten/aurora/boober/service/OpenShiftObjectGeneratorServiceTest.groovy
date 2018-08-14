package no.skatteetaten.aurora.boober.service

import groovy.json.JsonSlurper
import io.fabric8.kubernetes.api.model.OwnerReference
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal

class OpenShiftObjectGeneratorServiceTest extends AbstractOpenShiftObjectGeneratorTest {

  OpenShiftObjectGenerator objectGenerator = createObjectGenerator()

  def "service target must refer to toxiproxy if toxiproxy is enabled in deployment spec for java"() {

    given: "default deployment spec with toxiproxy version 2.1.3 enabled"
      AuroraDeploymentSpecInternal deploymentSpec = specJavaWithToxiproxy()
      Map<String, String> serviceLabels = [:]

    when: "service object has been created"
      def svc = objectGenerator.generateService(deploymentSpec, serviceLabels, new OwnerReference())
      svc = new JsonSlurper().parseText(svc.toString()) // convert to groovy for easier navigation and validation

    then: "the svc must contain toxiproxy http"
      def ports = svc.spec.ports

      ports.find { it.name == "http" && it.port == 80 && it.targetPort == 8090 }
  }

}
