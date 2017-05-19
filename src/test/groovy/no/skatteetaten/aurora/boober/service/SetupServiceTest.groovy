package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration,
    SetupService,
    AuroraDeploymentConfigService,
    OpenShiftObjectGenerator,
    OpenshiftTemplateApplier,
    Config])
class SetupServiceTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    UserDetailsProvider userDetailsProvider() {

      factory.Mock(UserDetailsProvider)
    }

    @Bean
    OpenShiftClient openshiftClient() {
      factory.Mock(OpenShiftClient)
    }

    @Bean
    OpenShiftResourceClient resourceClient() {
      factory.Mock(OpenShiftResourceClient)
    }
  }

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  OpenShiftResourceClient resourceClient

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  SetupService setupService

  @Autowired
  ObjectMapper mapper

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")
    openShiftClient.isValidUser(_) >> true
    openShiftClient.isValidGroup(_) >> true
    openShiftClient.applyMany(_, _) >> []
  }

  def "Should setup process for application"() {
    def processAid = new ApplicationId("booberdev", "tvinn")

    given:
      def templateResult = this.getClass().getResource("/openshift-objects/atomhopper-new.json")
      JsonNode jsonResult = mapper.readTree(templateResult)
      resourceClient.post("processedtemplate", null, _, _) >>
          new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)

      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupService.executeSetup(auroraConfig, [processAid], [])

    then:
      result.size() == 1
      result.get(0).auroraDc.type == TemplateType.process
  }

  def "Should setup development for application"() {
    given:
      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupService.executeSetup(auroraConfig, [aid], [])

    then:
      result.size() == 1
      result.get(0).auroraDc.type == TemplateType.development

  }

  def "Should setup deploy for application"() {
    given:
      def consoleAid = new ApplicationId(ENV_NAME, "console")
      def auroraConfig = AuroraConfigHelperKt.auroraConfigSamples

    when:
      def result = setupService.executeSetup(auroraConfig, [consoleAid], [])

    then:
      result.size() == 1
      result.get(0).auroraDc.type == TemplateType.deploy
  }
}
