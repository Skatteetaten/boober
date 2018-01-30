package no.skatteetaten.aurora.boober.model

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

import no.skatteetaten.aurora.boober.mapper.AuroraConfigException

class AuroraDeploymentSpecBuilderTest extends AbstractAuroraDeploymentSpecTest {

  def auroraConfigJson = defaultAuroraConfig()

  def "fileName can be long if both artifactId and name exist"() {
    given:
      auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{ "type" : "deploy", "groupId" : "foo", "version": "1"}'''
      auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{ "name" : "foo", "artifactId" : "foo"}'''

    when:
      createDeploymentSpec(auroraConfigJson, aid("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))

    then:
      notThrown(AuroraConfigException)
  }

  def "Fails when application name is too long and artifactId blank"() {
    given:
      auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{ "type" : "deploy", "groupId" : "foo", "version": "1"}'''
      auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{ "name" : "foo"}'''

    when:
      createDeploymentSpec(auroraConfigJson, aid("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))

    then:
      thrown(AuroraConfigException)
  }

  def "Should allow AuroraConfig for ApplicationIf with no name or artifactId"() {
    given:
      auroraConfigJson["reference.json"] = REFERENCE
      auroraConfigJson["utv/reference.json"] = '''{}'''

    when:
      def spec = createDeploymentSpec(auroraConfigJson, aid("utv", "reference"))

      spec
    then:
      notThrown(AuroraConfigException)
  }

  def "Fails when application name is too long and artifactId and name is blank"() {
    given:
      auroraConfigJson["this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{ "type" : "deploy", "groupId" : "foo", "version": "1"}'''
      auroraConfigJson["utv/this-name-is-stupid-stupid-stupidly-long-for-no-reason.json"] = '''{}'''

    when:
      createDeploymentSpec(auroraConfigJson, aid("utv", "this-name-is-stupid-stupid-stupidly-long-for-no-reason"))

    then:
      thrown(AuroraConfigException)
  }

  def "Fails when envFile does not start with about"() {
    given:
      auroraConfigJson["utv/foo.json"] = '''{ }'''
      auroraConfigJson["utv/aos-simple.json"] = '''{ "envFile": "foo.json" }'''

    when:
      createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      thrown(AuroraConfigException)
  }

  def "Disabling certificate with simplified config over full config"() {
    given:
      modify(auroraConfigJson, "aos-simple.json", {
        certificate = [commonName: "some_common_name"]
      })

    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
    then:
      deploymentSpec.deploy.certificateCn == "some_common_name"

    when:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "certificate": false }'''
      deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
    then:
      !deploymentSpec.deploy.certificateCn
  }

  def "Verify that it is possible to set some common global config options for templates even if they are not directly supported by that type"() {
    given:

      // certificate, splunkIndex and config are not directly supported by type=template
      auroraConfigJson = [
          "about.json"         : '''{
  "schemaVersion": "v1",
  "permissions": {
    "admin": "APP_PaaS_utv"
  },
  "affiliation" : "aos",
  
  "certificate": { 
    "commonName":"test"
  },
  "splunkIndex": "test",
  "database": true,
  "config": {
    "A_STANDARD_CONFIG": "a default value for all applications"
  }
}''',
          "utv/about.json"     : DEFAULT_UTV_ABOUT,
          "aos-simple.json"    : AOS_SIMPLE_JSON,
          "atomhopper.json"    : ATOMHOPPER,
          "utv/aos-simple.json": '''{ }''',
          "utv/atomhopper.json": '''{ }'''
      ]

      modify(auroraConfigJson, "aos-simple.json", {
        delegate.remove("certificate")
      })

    when:
      createDeploymentSpec(auroraConfigJson, aid("utv", "atomhopper"))
    then:
      noExceptionThrown()
  }

  def "Should fail when name is not valid DNS952 label"() {

    given:
      def aid = DEFAULT_AID
      modify(auroraConfigJson, "${aid.environment}/${aid.application}.json", {
        put("name", "test%qwe)")
      })
    when:
      def deploymentSpec = createDeploymentSpec(auroraConfigJson, aid)
      println deploymentSpec

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].field.handler.name == 'name'
  }

  def "Should throw AuroraConfigException due to missing required properties"() {

    given:
      def aid = DEFAULT_AID
      modify(auroraConfigJson, "${aid.application}.json", {
        remove("version")
      })
    when:
      createDeploymentSpec(auroraConfigJson, aid)

    then:
      def ex = thrown(AuroraConfigException)
      ex.errors[0].message == "Version must be set as string"
  }

  def "Fails when affiliation is not in about file"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "affiliation": "aaregistere" }'''

    when:
      createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      def e = thrown(AuroraConfigException)
      e.message ==
          "Config for application aos-simple in environment utv contains errors. Invalid Source field=affiliation requires an about source. Actual source is source=utv/aos-simple.json."
  }

  def "Fails when affiliation is too long"() {
    given:
      auroraConfigJson["utv/about.json"] = '''{ "affiliation": "aaregistere", "cluster" : "utv" }'''

    when:
      createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      def e = thrown(AuroraConfigException)
      e.message ==
          "Config for application aos-simple in environment utv contains errors. Affiliation can only contain letters and must be no longer than 10 characters."
  }

  def "createDeploymentSpec has correctly parsed AuroraConfig secretVault with old syntax"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "secretVault": "vaultName" }'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      deploymentSpec.getVolume().secretVaultName == "vaultName"
      deploymentSpec.getVolume().secretVaultKeys.size() == 0

    then:
      true
  }

  def "createDeploymentSpec has correctly parsed AuroraConfig secretVault with new syntax (without keys)"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "secretVault": {"name": "test"} }'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      deploymentSpec.getVolume().secretVaultName == "test"
      deploymentSpec.getVolume().secretVaultKeys.size() == 0

    then:
      true
  }

  def "createDeploymentSpec has correctly parsed AuroraConfig secretVault with new syntax (with empty keys)"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "secretVault": {"name": "test", "keys": []} }'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      deploymentSpec.getVolume().secretVaultName == "test"
      deploymentSpec.getVolume().secretVaultKeys.size() == 0

    then:
      true
  }

  def "createDeploymentSpec has correctly parsed AuroraConfig secretVault with new syntax (with keys)"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "secretVault": {"name": "test", "keys": ["test1", "test2"]} }'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      deploymentSpec.getVolume().secretVaultName == "test"
      deploymentSpec.getVolume().secretVaultKeys.size() == 2

    then:
      true
  }
}
