package no.skatteetaten.aurora.boober.facade

/*
TODO: Rething entire concept
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT

import java.time.Instant

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureWebClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.core.authority.SimpleGrantedAuthority

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.utils.SharedSecretReader
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftGroups
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.openshift.UserGroup
import no.skatteetaten.aurora.boober.service.openshift.token.UserDetailsTokenProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ExternalResourceProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.Instants
import spock.mock.DetachedMockFactory

@AutoConfigureWebClient(registerRestTemplate = true)
@SpringBootTest(classes = [
    Configuration,
    DeployService,
    OpenShiftObjectGenerator,
    OpenShiftCommandService,
    OpenShiftTemplateProcessor,
    GitServices,
    EncryptionService,
    AuroraConfigService,
    VaultService,
    ObjectMapper,
    Config,
    AuroraMetrics,
    UserDetailsTokenProvider,
    AuroraDeploymentSpecValidator,
    SharedSecretReader,
    OpenShiftObjectLabelService,
    BitbucketRestTemplateWrapper
])
class AbstractMockedOpenShiftSpecification extends AbstractSpec {

  @TestConfiguration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    MeterRegistry meterRegistry() {
      Metrics.globalRegistry
    }

    @Bean
    OpenShiftClient openShiftClient() {
      factory.Mock(OpenShiftClient)
    }

    @Bean
    RedeployService redeployService() {
      factory.Mock(RedeployService)
    }

    @Bean
    UserDetailsProvider userDetailsProvider() {
      factory.Mock(UserDetailsProvider)
    }

    @Bean
    BitbucketService bitbucketService() {
      factory.Mock(BitbucketService)
    }

    @Bean
    StsProvisioner stsProvisioner() {
      factory.Mock(StsProvisioner)
    }

    @Bean
    DatabaseSchemaProvisioner dbClient() {
      factory.Mock(DatabaseSchemaProvisioner)
    }

    @Bean
    DockerService dockerService() {
      factory.Mock(DockerService)
    }

    @Bean
    ExternalResourceProvisioner externalResourceProvisioner() {
      factory.Mock(ExternalResourceProvisioner)
    }

    @Bean
    @OpenShiftResourceClientConfig.ClientType(API_USER)
    @Primary
    OpenShiftResourceClient resourceClient() {

      factory.Mock(OpenShiftResourceClient)
    }

    @Bean
    @OpenShiftResourceClientConfig.ClientType(SERVICE_ACCOUNT)
    OpenShiftResourceClient resourceClientSA() {

      factory.Mock(OpenShiftResourceClient)
    }

    @Bean
    DeployLogService deployLogService() {
      factory.Mock(DeployLogService)
    }
  }

  @Autowired
  private GitService auroraConfigGitService

  @Autowired
  private VaultService vaultService

  @Autowired
  private UserDetailsProvider userDetailsProvider

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  private AuroraConfigService auroraConfigService

  @Autowired
  ObjectMapper mapper

  def setup() {

    Instants.determineNow = { Instant.EPOCH }

    def currentFeature = specificationContext.currentFeature
    DefaultOverride defaultOverride = currentFeature.featureMethod.getAnnotation(DefaultOverride)
    defaultOverride = defaultOverride ?: currentFeature.parent.getAnnotation(DefaultOverride)
    boolean useInteractions = defaultOverride ? defaultOverride.interactions() : true
    boolean useAuroraConfig = defaultOverride ? defaultOverride.auroraConfig() : true

    if (useInteractions) {
      userDetailsProvider.authenticatedUser >> getAuthenticatedUser()

      openShiftClient.getGroups() >> new OpenShiftGroups([
          new UserGroup("foo", "APP_PaaS_drift"),
          new UserGroup("foo", "APP_PaaS_utv")])
    }

    if (useAuroraConfig) {

      userDetailsProvider.authenticatedUser >> getAuthenticatedUser()

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
      GitServiceHelperKt.recreateEmptyBareRepos(auroraConfig.getName())
      GitServiceHelperKt.recreateRepo(new File("/tmp/vaulttest/aos"))
      GitServiceHelperKt.recreateRepo(new File("/tmp/boobertest/aos"))

      vaultService.createOrUpdateFileInVault("aos", "foo", "latest.properties", "FOO=BAR".bytes as byte[])
      auroraConfigService.save(auroraConfig)
    }
  }

  protected User getAuthenticatedUser() {
    new User("hero", "token", "Test User", [new SimpleGrantedAuthority("APP_PaaS_utv")])
  }

  void createRepoAndSaveFiles(AuroraConfig auroraConfig) {

    GitServiceHelperKt.recreateEmptyBareRepos(auroraConfig.getName())
    auroraConfigService.save(auroraConfig)
  }

  def createOpenShiftResponse(String kind, OperationType operationType, int prevVersion, int currVersion) {
    def previous = mapper.
        convertValue(["kind": kind, "metadata": ["labels": ["releasedVersion": prevVersion]]], JsonNode.class)
    def response = mapper.
        convertValue(["kind": kind, "metadata": ["labels": ["releasedVersion": currVersion]]], JsonNode.class)

    return new OpenShiftResponse(new OpenshiftCommand(operationType, Mock(JsonNode), previous, null), response)
  }
}

import java.time.Duration

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import spock.lang.Unroll

// TODO: Denne mocker IKKE http men mockk på hver tjeneste. Gir det verdi?
// TODO: Tar mye tid. 2.5 sek stykke. This is the only test that uses this abstract class
class DeployServiceTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployService deployService

  @Autowired
  RedeployService redeployService

  @Autowired
  ObjectMapper mapper

  @Autowired
  AuroraConfigService auroraConfigService

  @Autowired
  DeployLogService deployLogService

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "aos-simple"
  def affiliation = "aos"
  def configRef = new AuroraConfigRef(affiliation, "master", "123")

  final ApplicationDeploymentRef adr = new ApplicationDeploymentRef(ENV_NAME, APP_NAME)

  def setup() {
    openShiftClient.projectExists(_) >> {
      false
    }

    openShiftClient.performOpenShiftCommand(_, _) >> {
      def cmd = it[1]
      def namespace = it[0]

      def name = cmd.payload.at("/metadata/name").textValue()
      def kind = cmd.payload.at("/kind").textValue().toLowerCase()
      try {
        def fileName = "$namespace-${name}-${kind}.json"
        def resource = loadResource(fileName)
        new OpenShiftResponse(cmd, mapper.readTree(resource))
      } catch (Exception ignored) {
        new OpenShiftResponse(cmd, cmd.payload)
      }
    }

    openShiftClient.resourceExists(_, _, _) >> true
    openShiftClient.getByLabelSelectors(_, _, _) >> []
    redeployService.triggerRedeploy(_, _) >> new RedeployService.RedeployResult()
    deployLogService.markRelease(_, _) >> {
      it[0]
    }
  }

  def "Should prepare deploy environment for new project with ttl"() {
    given:
      def ref = new AuroraConfigRef(affiliation, "master", "123")
      def ads = auroraConfigService.
          createValidatedAuroraDeploymentSpecs(ref, [new ApplicationDeploymentRef(ENV_NAME, APP_NAME)])

    when:
      def deployResults = deployService.prepareDeployEnvironments(ads)

    then:
      deployResults.size() == 1
      def env = deployResults.keySet().first()
      env.ttl == Duration.ofDays(1)

      def deployResult = deployResults.values().first()
      def namespace = deployResult.openShiftResponses.find { it.command.payload.at("/kind").textValue() == "Namespace" }
      namespace != null
      namespace.command.payload.at("/metadata/labels/removeAfter").textValue() == "86400"
  }

  def "Throw IllegalArgumentException if no applicationDeploymentRef is specified"() {
    when:
      deployService.executeDeploy(configRef, [])

    then:
      thrown(IllegalArgumentException)
  }

  @Unroll
  def "Execute deploy for #env/#name"() {
    when:
      def results = deployService.executeDeploy(configRef, [new ApplicationDeploymentRef(env, name)])

    then:
      results.success
      results.auroraDeploymentSpecInternal
      results.deployId
      results.openShiftResponses.size() > 0

    where:
      env           | name
      'booberdev'   | 'reference'
      'booberdev'   | 'console'
      'webseal'     | 'sprocket'
      'booberdev'   | 'sprocket'
      'booberdev'   | 'reference-web'
      'booberdev'   | 'aos-simple'
      'secrettest'  | 'aos-simple'
      'release'     | 'aos-simple'
      'mounts'      | 'aos-simple'
      'secretmount' | 'aos-simple'
  }

  def "Throw error if override file is not used"() {
    given:
      def overrides = [
          new AuroraConfigFile("utv/foobar", "foobar", true, false)
      ]
    when:

      deployService.executeDeploy(configRef, [new ApplicationDeploymentRef("booberdev", 'reference')], overrides)

    then:
      def e = thrown(RuntimeException)
      e.message=="Overrides files 'utv/foobar' does not apply to any deploymentReference (booberdev/reference)"

  }
}
*/
