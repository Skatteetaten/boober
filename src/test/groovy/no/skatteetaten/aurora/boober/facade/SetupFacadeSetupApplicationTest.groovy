package no.skatteetaten.aurora.boober.facade

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.internal.SetupParams
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.service.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import no.skatteetaten.aurora.boober.service.EncryptionService
import no.skatteetaten.aurora.boober.service.GitService
import no.skatteetaten.aurora.boober.service.GitServiceHelperKt
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.OpenShiftTemplateProcessor
import no.skatteetaten.aurora.boober.service.SecretVaultService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    SetupFacade,
    AuroraConfigService,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
    GitService,
    SecretVaultService,
    VaultFacade,
    EncryptionService,
    OpenShiftClient,
    AuroraConfigFacade,
    Config
]
    , properties = [
        "boober.git.urlPattern=/tmp/boober-test/%s",
        "boober.git.checkoutPath=/tmp/boober",
        "boober.git.username=",
        "boober.git.password="
    ])
class SetupFacadeSetupApplicationTest extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    UserDetailsProvider userDetailsProvider() {

      factory.Mock(UserDetailsProvider)
    }

    @Bean
    OpenShiftResourceClient resourceClient() {
      factory.Mock(OpenShiftResourceClient)
    }
  }

  @Autowired
  OpenShiftResourceClient resourceClient

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  SetupFacade setupFacade

  @Autowired
  AuroraConfigFacade configFacade

  @Autowired
  ObjectMapper mapper

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  def affiliation = "aos"

  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  def okJson(def obj) {
    return new ResponseEntity<JsonNode>(mapper.convertValue(obj, JsonNode.class), HttpStatus.OK)
  }

  def setup() {
    userDetailsProvider.getAuthenticatedUser() >> new User("test", "test", "Test User")

    //test is a valid user
    resourceClient.getExistingResource(_, _) >> { args ->
      def url = args[1]
      if (url.contains("labelSelector")) {
        if (url.contains("deploymentconfigs")) {
          okJson([items: [["kind": "DeploymentConfig", "metadata": ["name": "Foo"]]]])
        } else {
          okJson([items: []])
        }
      } else {
        new ResponseEntity<JsonNode>(mapper.convertValue(["kind": "Users", "users": ["test"]], JsonNode.class),
            HttpStatus.OK)
      }
    }

    //we have a image stream that gets a new version. It is changed.
    resourceClient.get(_, _, _) >> { args ->

      def kind = args[0]
      if (kind == "imagestream") {
        okJson([metadata: [resourceVersion: "123"]])
      } else {
        null
      }
    }
    resourceClient.put(_, _, _, _) >> { okJson([kind: "ImageStream", metadata: [resourceVersion: "1235"]]) }


    resourceClient.post(_, _, _, _) >> { new ResponseEntity<JsonNode>(it[3], HttpStatus.OK) }


    resourceClient.delete(_, _, _, _) >> { new ResponseEntity<JsonNode>(it[3], HttpStatus.OK) }

  }

  private void createRepoAndSaveFiles(String affiliation, AuroraConfig auroraConfig) {
    GitServiceHelperKt.createInitRepo(affiliation)
    configFacade.saveAuroraConfig(affiliation, auroraConfig, false)
  }

  def "Should setup application with config change, delete old object and do manual redeploy"() {
    given:
      GitServiceHelperKt.createInitRepo(affiliation)
      def auroraConfig = AuroraConfigHelperKt.createAuroraConfig(aid, "aos")
      createRepoAndSaveFiles("aos", auroraConfig)

    when:

      def res = setupFacade.executeSetup(affiliation, new SetupParams([ENV_NAME], [APP_NAME], []))

    then:
      def responses = res[0].openShiftResponses
      def typeCounts = responses.countBy { it.command.operationType }
      def kinds = responses.collect { it.command.payload.get("kind").asText() } toSet()

      typeCounts[OperationType.CREATE] == 8
      typeCounts[OperationType.DELETE] == 1
      typeCounts[OperationType.UPDATE] == 1

      kinds ==
          ['ConfigMap', 'ProjectRequest', 'Service', 'ImageStream', 'BuildConfig', 'RoleBinding', 'DeploymentConfig', 'BuildRequest', 'Route'] as Set

  }

}
