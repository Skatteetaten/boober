package no.skatteetaten.aurora.boober.scripts

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.model.ApplicationId

class DevSetupTest extends AbstractAuroraDeploymentSpecTest {

  def "Smoke test to verify the dev file is valid"() {

    given:
      def file = new JsonSlurper().parseText(new File("scripts/devsetup/files/reference.json").text)
      def jsonFiles = file.files.collectEntries { k, v -> [(k): JsonOutput.toJson(v)] }

    when:
      def deploymentSpec = createDeploymentSpec(jsonFiles, ApplicationId.aid("boober-bugfix", "reference"))

    then:
      true
  }
}
