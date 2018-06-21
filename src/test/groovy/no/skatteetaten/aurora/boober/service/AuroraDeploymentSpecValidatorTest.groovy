package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftGroups
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.openshift.UserGroup
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.vault.VaultService

class AuroraDeploymentSpecValidatorTest extends AbstractAuroraDeploymentSpecTest {

  def auroraConfigJson = defaultAuroraConfig()

  def udp = Mock(UserDetailsProvider)
  def openShiftClient = Mock(OpenShiftClient)
  def dbClient = Mock(DatabaseSchemaProvisioner)
  def vaultService = Mock(VaultService)

  def processor = new OpenShiftTemplateProcessor(udp, Mock(OpenShiftResourceClient), new ObjectMapper())
  def specValidator = new AuroraDeploymentSpecValidator(openShiftClient, processor, dbClient, vaultService, "utv")

  def mapper = new ObjectMapper()

  def setup() {
    udp.authenticatedUser >> new User("hero", "token", "Test User", [])
  }

  def "Fails when admin groups is empty"() {
    given:
      auroraConfigJson["utv/about.json"] = '''{ "permissions": { "admin": "" }, "cluster" : "utv" }'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      specValidator.assertIsValid(deploymentSpec)

    then:
      thrown(AuroraDeploymentSpecValidationException)
  }

  def "Fails when admin groups does not exist"() {
    given:
      auroraConfigJson["utv/about.json"] = '''{ "permissions": { "admin": "APP_PaaS_utv" }, "cluster" : "utv" }'''
      openShiftClient.getGroups() >> new OpenShiftGroups([])
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      specValidator.assertIsValid(deploymentSpec)

    then:
      thrown(AuroraDeploymentSpecValidationException)
  }

  def "Fails when databaseId does not exist"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "database": { "foo" : "123-123-123" } }'''
      openShiftClient.getGroups() >> new OpenShiftGroups([new UserGroup("foo", "APP_PaaS_utv")])
      openShiftClient.getTemplate("atomhopper") >> null
      dbClient.findSchemaById("123-123-123") >> true
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      specValidator.assertIsValid(deploymentSpec)

    then:
      def e = thrown(AuroraDeploymentSpecValidationException)
      e.message == "Database schema with id=123-123-123 does not exist"
  }

  def "Succeeds when databaseId does not exist when not on current cluster"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "cluster": "qa", "database": { "foo" : "123-123-123" } }'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
      dbClient.findSchemaById("123-123-123") >> { throw new ProvisioningException("") }

    when:
      specValidator.validateDatabaseId(deploymentSpec)

    then:
      true
  }

  def "Fails when template does not exist"() {
    given:
      auroraConfigJson["aos-simple.json"] = '''{ "type": "template", "name": "aos-simple", "template": "atomhopper" }'''

      openShiftClient.getGroups() >> new OpenShiftGroups([new UserGroup("foo", "APP_PaaS_utv")])
      openShiftClient.getTemplate("atomhopper") >> null
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      specValidator.assertIsValid(deploymentSpec)

    then:
      thrown(AuroraDeploymentSpecValidationException)
  }

  def "Fails when parameters not in template"() {
    given:
      auroraConfigJson["aos-simple.json"] = '''{ "type": "template", "name": "aos-simple", "template": "atomhopper", "parameters" : { "FOO" : "BAR"} }'''

      openShiftClient.getGroups() >> new OpenShiftGroups([new UserGroup("foo", "APP_PaaS_utv")])
      openShiftClient.getTemplate("atomhopper") >> {
        def templateFileName = "/samples/processedtemplate/booberdev/tvinn/atomhopper.json"
        def templateResult = this.getClass().getResource(templateFileName)
        mapper.readTree(templateResult)
      }
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      specValidator.assertIsValid(deploymentSpec)

    then:
      def e = thrown(AuroraDeploymentSpecValidationException)
      e.message ==
          "Required template parameters [FEED_NAME, DB_NAME, DOMAIN_NAME] not set. Template does not contain parameter(s) [FOO]"
  }

  def "Fails when template does not contain required fields"() {
    given:
      auroraConfigJson["aos-simple.json"] = '''{ "type": "template", "name": "aos-simple", "template": "atomhopper" }'''

      openShiftClient.getGroups() >> new OpenShiftGroups([new UserGroup("foo", "APP_PaaS_utv")])
      openShiftClient.getTemplate("atomhopper") >> {
        def templateFileName = "/samples/processedtemplate/booberdev/tvinn/atomhopper.json"
        def templateResult = this.getClass().getResource(templateFileName)
        mapper.readTree(templateResult)
      }
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      specValidator.assertIsValid(deploymentSpec)

    then:
      def e = thrown(AuroraDeploymentSpecValidationException)
      e.message == "Required template parameters [FEED_NAME, DB_NAME, DOMAIN_NAME] not set"
  }

  def "Fails when vault does not exist"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "secretVault": "test", "mounts": { "secret": { "type": "Secret", "secretVault": "test2", "path": "/tmp" } } }'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
      def vaultCollection = deploymentSpec.environment.affiliation

      vaultService.vaultExists(vaultCollection, "test2") >> true
      vaultService.vaultExists(vaultCollection, "test") >> false

    when:
      specValidator.validateVaultExistence(deploymentSpec)

    then:
      def e = thrown(AuroraDeploymentSpecValidationException)
      e.message == "Referenced Vault test in Vault Collection $vaultCollection does not exist"
  }

  def "Succeeds when vault exists"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "secretVault": "test", "mounts": { "secret": { "type": "Secret", "secretVault": "test2", "path": "/tmp" } } }'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
      def vaultCollection = deploymentSpec.environment.affiliation

      vaultService.vaultExists(vaultCollection, "test2") >> true
      vaultService.vaultExists(vaultCollection, "test") >> true

    expect:
      specValidator.validateVaultExistence(deploymentSpec)
  }

  def "should handle overrides of secretVaultKeys with different syntax"() {
    given:
      auroraConfigJson["utv/about.json"] = '''{ "secretVault": "foo", "cluster" : "utv" }'''
      auroraConfigJson["utv/aos-simple.json"] = '''{ "secretVault": { "name":"foo2" }}'''

    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      deploymentSpec.volume.secretVaultName == "foo2"
  }

  def "Fails when secretVault keyMappings contains values not in keys"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "secretVault": { "name": "test", "keys":["test-key1"], "keyMappings": { "test-key2":"value" } }}'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      specValidator.validateKeyMappings(deploymentSpec)

    then:
      def e = thrown(AuroraDeploymentSpecValidationException)
      e.message == "The secretVault keyMappings [test-key2] were not found in keys"
  }

  def "Succeeds when secretVault contains keyMappings but no keys"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "secretVault": { "name": "test", "keyMappings": { "test-key2":"value" } }}'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    expect:
      specValidator.validateKeyMappings(deploymentSpec)
  }

  def "Fails when key in auroraConfig is not present in secretVault"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "secretVault": { "name": "test", "keys": ["test-key1"] }}'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
      vaultService.findVaultKeys("aos", "test", "latest.properties") >> ["test-key2"]

    when:
      specValidator.validateSecretVaultKeys(deploymentSpec)

    then:
      def e = thrown(AuroraDeploymentSpecValidationException)
      e.message == "The keys [test-key1] were not found in the secret vault"
  }
}
