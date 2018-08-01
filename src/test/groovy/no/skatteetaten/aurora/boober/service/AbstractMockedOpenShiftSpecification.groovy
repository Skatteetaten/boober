package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT

import java.time.Instant

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureWebClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
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
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.Instants
import spock.mock.DetachedMockFactory

@AutoConfigureWebClient(registerRestTemplate = true)
@ActiveProfiles("local")
@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    DeployService,
    OpenShiftObjectGenerator,
    OpenShiftCommandBuilder,
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
    BitbucketProjectService
])
class AbstractMockedOpenShiftSpecification extends AbstractSpec {

  @Configuration
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
    BitbucketProjectService bitbucketProjectService() {
      factory.Mock(BitbucketProjectService)
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

    Instants.determineNow = {Instant.EPOCH }

    def currentFeature = specificationContext.currentFeature
    DefaultOverride defaultOverride = currentFeature.featureMethod.getAnnotation(DefaultOverride)
    defaultOverride = defaultOverride ?: currentFeature.parent.getAnnotation(DefaultOverride)
    boolean useInteractions = defaultOverride ? defaultOverride.interactions() : true
    boolean useAuroraConfig = defaultOverride ? defaultOverride.auroraConfig() : true

    if (useInteractions) {
      userDetailsProvider.authenticatedUser >> new User("hero", "token", "Test User", [])

      openShiftClient.getGroups() >> new OpenShiftGroups([
          new UserGroup("foo", "APP_PaaS_drift"),
          new UserGroup("foo", "APP_PaaS_utv")])
    }

    if (useAuroraConfig) {

      userDetailsProvider.authenticatedUser >> new User("hero", "token", "Test User", [])

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
      GitServiceHelperKt.recreateEmptyBareRepos(auroraConfig.getName())
      GitServiceHelperKt.recreateRepo(new File("/tmp/vaulttest/aos"))
      GitServiceHelperKt.recreateRepo(new File("/tmp/boobertest/aos"))

      vaultService.createOrUpdateFileInVault("aos", "foo", "latest.properties", "FOO=BAR".bytes as byte[])
      auroraConfigService.save(auroraConfig)
    }
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
