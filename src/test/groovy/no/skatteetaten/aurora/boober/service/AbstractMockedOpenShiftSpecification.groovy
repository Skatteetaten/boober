package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.API_USER
import static no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig.TokenSource.SERVICE_ACCOUNT

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.facade.VaultFacade
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraSecretVault
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClientConfig
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
    Config,
    UserDetailsTokenProvider
])
class AbstractMockedOpenShiftSpecification extends Specification {

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

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
  VaultFacade vaultFacade

  @Autowired
  UserDetailsProvider userDetailsProvider

  @Autowired
  OpenShiftClient openShiftClient

  @Autowired
  DeployBundleService deployBundleService

  def setup() {

    def currentFeature = specificationContext.currentFeature
    DefaultOverride defaultOverride = currentFeature.featureMethod.getAnnotation(DefaultOverride)
    defaultOverride = defaultOverride ?: currentFeature.parent.getAnnotation(DefaultOverride)
    boolean useInteractions = defaultOverride ? defaultOverride.interactions() : true
    boolean useAuroraConfig = defaultOverride ? defaultOverride.auroraConfig() : true

    if (useInteractions) {
      userDetailsProvider.authenticatedUser >> new User("hero", "token", "Test User")

      openShiftClient.isValidGroup(_) >> true
      openShiftClient.isValidUser(_) >> true
      openShiftClient.hasUserAccess(_, _) >> true
    }

    if (useAuroraConfig) {

      def vault = new AuroraSecretVault("foo", ["latest.properties": "Rk9PPWJhcgpCQVI9YmF6Cg=="], null, [:])
      userDetailsProvider.authenticatedUser >> new User("hero", "token", "Test User")
      openShiftClient.hasUserAccess(_, _) >> true

      AuroraConfig auroraConfig = AuroraConfigHelperKt.auroraConfigSamples
      GitServiceHelperKt.createInitRepo(auroraConfig.affiliation)
      vaultFacade.save("aos", vault, false)
      deployBundleService.saveAuroraConfig(auroraConfig, false)

    }
  }

  void createRepoAndSaveFiles(AuroraConfig auroraConfig) {

    GitServiceHelperKt.createInitRepo(auroraConfig.affiliation)
    deployBundleService.saveAuroraConfig(auroraConfig, false)
  }
}
