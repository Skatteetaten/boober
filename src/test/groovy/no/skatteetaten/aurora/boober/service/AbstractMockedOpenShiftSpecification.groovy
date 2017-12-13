package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import no.skatteetaten.aurora.AuroraMetrics
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.service.internal.SharedSecretReader
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResponse
import no.skatteetaten.aurora.boober.service.openshift.OpenshiftCommand
import no.skatteetaten.aurora.boober.service.openshift.OperationType
import no.skatteetaten.aurora.boober.service.openshift.UserDetailsTokenProvider
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    DeployService,
    OpenShiftObjectGenerator,
    OpenShiftTemplateProcessor,
    GitService,
    SecretVaultService,
    EncryptionService,
    DeployBundleService,
    VaultFacade,
    ObjectMapper,
    SecretVaultPermissionService,
    Config,
    AuroraMetrics,
    UserDetailsTokenProvider,
    AuroraDeploymentSpecValidator,
    SharedSecretReader,
    VelocityTemplateJsonService,
    OpenShiftObjectLabelService,
    RedeployService
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
    UserDetailsProvider userDetailsProvider() {
      factory.Mock(UserDetailsProvider)
    }

    @Bean
    DockerService dockerService() {
      factory.Mock(DockerService)
    }

    @Bean
    OpenShiftResourceClient client() {
      factory.Mock(OpenShiftResourceClient)
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
  }

  @Autowired
  GitService gitService

  @Autowired
  VaultFacade vaultFacade

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployBundleService deployBundleService

  @Autowired
  ObjectMapper mapper

  def git

  def cleanup() {
    if (git != null) {
      gitService.closeRepository(git)
    }
  }

  def setup() {

    def currentFeature = specificationContext.currentFeature
    DefaultOverride defaultOverride = currentFeature.featureMethod.getAnnotation(DefaultOverride)
    defaultOverride = defaultOverride ?: currentFeature.parent.getAnnotation(DefaultOverride)
    boolean useInteractions = defaultOverride ? defaultOverride.interactions() : true
    boolean useAuroraConfig = defaultOverride ? defaultOverride.auroraConfig() : true

    if (useInteractions) {
      userDetailsProvider.authenticatedUser >> new User("hero", "token", "Test User")

      openShiftClient.isValidGroup(_) >> true
    }

    if (useAuroraConfig) {

      def vault = new AuroraSecretVault("foo", ["latest.properties": "Rk9PPWJhcgpCQVI9YmF6Cg=="], null, [:])
      userDetailsProvider.authenticatedUser >> new User("hero", "token", "Test User")

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
      gitService.deleteFiles(auroraConfig.affiliation)
      GitServiceHelperKt.createInitRepo(auroraConfig.affiliation)

      vaultFacade.save("aos", vault, false)
      deployBundleService.saveAuroraConfig(auroraConfig, false)
      git = gitService.openRepo(auroraConfig.affiliation)
    }
  }

  void createRepoAndSaveFiles(AuroraConfig auroraConfig) {

    gitService.deleteFiles(auroraConfig.affiliation)
    GitServiceHelperKt.createInitRepo(auroraConfig.affiliation)
    deployBundleService.saveAuroraConfig(auroraConfig, false)
  }

  def createOpenShiftResponse(String kind, OperationType operationType, int prevVersion, int currVersion) {
    def previous = mapper.
        convertValue(["kind": kind, "metadata": ["labels": ["releasedVersion": prevVersion]]], JsonNode.class)
    def response = mapper.
        convertValue(["kind": kind, "metadata": ["labels": ["releasedVersion": currVersion]]], JsonNode.class)

    return new OpenShiftResponse(new OpenshiftCommand(operationType, Mock(JsonNode), previous, null), response)
  }
}
