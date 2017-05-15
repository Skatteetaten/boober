package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getQaEbsUsersSampleFiles
import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getSampleFiles

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.TemplateType
import no.skatteetaten.aurora.boober.service.internal.ApplicationConfigException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.validation.AuroraConfigFieldHandlerKt
import no.skatteetaten.aurora.boober.service.validation.AuroraDeploymentConfigMapperV1
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [
    no.skatteetaten.aurora.boober.Configuration,
    UserDetailsProvider,
    AuroraConfigService,
    GitService,
    OpenShiftClient,
    EncryptionService,
    AuroraDeploymentConfigService,
    AuroraDeploymentConfigMapperV1,
    OpenShiftResourceClient
])
class AuroraDeploymentConfigServiceTest extends Specification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"
  final ApplicationId aid = new ApplicationId(ENV_NAME, APP_NAME)

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    OpenShiftClient openShiftClient() {
      factory.Mock(OpenShiftClient)
    }

    @Bean
    OpenShiftResourceClient openshiftResourceClient() {
      factory.Mock(OpenShiftResourceClient)
    }

    @Bean
    UserDetailsProvider userDetailsProvider() {

      factory.Stub(UserDetailsProvider)
    }

    @Bean
    GitService gitService() {
      factory.Mock(GitService)
    }
  }

  @Autowired
  ObjectMapper mapper

  @Autowired
  AuroraDeploymentConfigService auroraDeploymentConfigService

  @Autowired
  AuroraDeploymentConfigMapperV1 mapperV1

  private static AuroraConfig createAuroraConfig(ApplicationId aid, Map<String, String> secrets = [:]) {
    Map<String, JsonNode> files = getSampleFiles(aid)
    new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, secrets)
  }

  def "Should return error when name is not valid DNS952 label"() {

    given:
      def overrideFile = mapper.convertValue(["name": "test%qwe)"], JsonNode.class)
      def overrides = [new AuroraConfigFile("${aid.environmentName}/${aid.applicationName}.json", overrideFile, true)]
      AuroraConfig auroraConfig = createAuroraConfig(aid)

    when:
      def auroraDc = auroraDeploymentConfigService.createAuroraDc(aid, auroraConfig, overrides)
      auroraDeploymentConfigService.validateAuroraDc(aid, auroraDc, false)
    then:
      thrown(ApplicationConfigException)

  }

  def "Should create AuroraDC for Console"() {
    given:
      def consoleAid = new ApplicationId("booberdev", "console")
      AuroraConfig auroraConfig = createAuroraConfig(consoleAid)

    when:
      def fields = AuroraConfigFieldHandlerKt.extractFrom(mapperV1.extractors, auroraConfig.auroraConfigFiles)
      def auroraDc = auroraDeploymentConfigService.createAuroraDc(consoleAid, auroraConfig, [])

    then:
      auroraDc.prometheus.port == 8081
      auroraDc.webseal.path == "/webseal"
  }

  def "Should create AuroraConfigFields"() {
    given:
      AuroraConfig auroraConfig = createAuroraConfig(aid)

    when:
      def fields = AuroraConfigFieldHandlerKt.extractFrom(mapperV1.extractors, auroraConfig.auroraConfigFiles)

    then:
      fields.size() == 21
  }

  def "Should create AuroraConfigFields with overrides"() {
    given:
      def overrideFile = mapper.convertValue(["type": "deploy", "cluster": "utv"], JsonNode.class)
      def overrides = [new AuroraConfigFile("booberdev/about.json", overrideFile, true)]
      AuroraConfig auroraConfig = createAuroraConfig(aid)
      def auroraConfigFiles = auroraConfig.getFilesForApplication(aid, overrides)

    when:
      def fields = AuroraConfigFieldHandlerKt.extractFrom(mapperV1.extractors, auroraConfigFiles)

    then:
      fields['cluster'].source == "booberdev/about.json.override"
      fields['cluster'].value.asText() == "utv"
      fields['type'].source == "booberdev/about.json.override"
      fields['type'].value.asText() == "deploy"
  }

  def "Should fail due to missing config file"() {

    given:
      Map<String, JsonNode> files = getQaEbsUsersSampleFiles()
      files.remove("${APP_NAME}.json" as String)
      def auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, [:])

    when:
      auroraConfig.getFilesForApplication(aid, [])

    then:
      thrown(IllegalArgumentException)
  }

  def "Should successfully merge all config files"() {

    given:
      AuroraConfig auroraConfig = createAuroraConfig(aid)

    when:
      AuroraDeploymentConfig auroraDc = auroraDeploymentConfigService.createAuroraDc(aid, auroraConfig, [])

    then:
      with(auroraDc) {
        namespace == "aos-${ENV_NAME}"
        affiliation == "aos"
        name == APP_NAME
        cluster == "utv"
        replicas == 1
        type == TemplateType.development
        permissions.admin.groups.containsAll(["APP_PaaS_drift", "APP_PaaS_utv"])
      }
  }

  def "Should override name property in 'app'.json with name in override"() {

    given:
      def overrideFile = mapper.convertValue(["name": "awesome-app"], JsonNode.class)
      def overrides = [new AuroraConfigFile("booberdev/about.json", overrideFile, true)]
      AuroraConfig auroraConfig = createAuroraConfig(aid)

    when:
      AuroraDeploymentConfig auroraDc = auroraDeploymentConfigService.
          createAuroraDc(aid, auroraConfig, overrides)

    then:
      auroraDc.name == "awesome-app"
  }

  def "Should throw ValidationException due to missing required properties"() {

    given: "AuroraConfig without build properties"
      Map<String, JsonNode> files = getSampleFiles(aid)
      (files.get("verify-ebs-users.json") as ObjectNode).remove("version")
      (files.get("booberdev/verify-ebs-users.json") as ObjectNode).remove("version")
      AuroraConfig auroraConfig =
          new AuroraConfig(files.collect { new AuroraConfigFile(it.key, it.value, false) }, [:])


    when:
      auroraDeploymentConfigService.createAuroraDc(aid, auroraConfig, [])

    then:
      def ex = thrown(ApplicationConfigException)
      ex.errors[0] == "Version must be set"
  }
}

