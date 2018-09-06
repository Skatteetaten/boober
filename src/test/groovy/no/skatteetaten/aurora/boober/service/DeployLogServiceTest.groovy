package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand

class DeployLogServiceTest extends AbstractAuroraDeploymentSpecTest {

  def "Should mark release"() {

    given:
      def bitbucketService = Mock(BitbucketService)
      def service = new DeployLogService(bitbucketService, new ObjectMapper(), "ao", "auroradeploymenttags")

      def auroraConfigRef = new AuroraConfigRef("test", "master", "123")
      def applicationDeploymentRef = new ApplicationDeploymentRef("foo", "bar")
      def command = new ApplicationDeploymentCommand([:], applicationDeploymentRef, auroraConfigRef)
      def deploymentSpec = createDeploymentSpec(defaultAuroraConfig(), DEFAULT_AID)
      def deployId = "12e456"

      def deployResult = new AuroraDeployResult(command, deploymentSpec, deployId, [], true, false,
          "DONE", null, false, null)
      def deployer = new Deployer("Test Testesen", "test0test.no")


      def fileName = "test/${deployId}.json"
      1 * bitbucketService.uploadFile("ao", "auroradeploymenttags", fileName, "DEPLOY/utv-foo/bar", _) >> "Success"

    when:
      def response = service.markRelease([deployResult], deployer)

    then:
      response.size() == 1
      response[0].bitbucketStoreResult == "Success"
  }

}
