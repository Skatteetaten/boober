package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    Configuration,
    SharedSecretReader,
    DeployLogService,
    Config]
)
class DeployLogServiceTest extends AbstractAuroraDeploymentSpecTest {

  @org.springframework.context.annotation.Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    BitbucketService bitbucketService() {
      factory.Mock(BitbucketService)
    }

    @Bean
    ObjectMapper mapper() { new ObjectMapper() }

    @Bean
    RestTemplateBuilder builder() { new RestTemplateBuilder() }
  }

  @Autowired
  BitbucketService bitbucketService

  @Autowired
  DeployLogService service

  def "Should mark release"() {

    given:
      def auroraConfigRef = new AuroraConfigRef("test", "master", "123")
      def applicationDeploymentRef = new ApplicationDeploymentRef("foo", "bar")
      def command = new ApplicationDeploymentCommand([:], applicationDeploymentRef, auroraConfigRef)
      def deploymentSpec = createDeploymentSpec(defaultAuroraConfig(), DEFAULT_AID)
      def deployId = "12e456"
      def openshiftResponses = []

      def deployResult = new AuroraDeployResult(command, deploymentSpec, deployId, openshiftResponses, true, false,
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
