package no.skatteetaten.aurora.boober.model

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import no.skatteetaten.aurora.boober.mapper.AuroraConfigException
import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentSpecBuilderKt

class AuroraDeploymentSpecBuilderTest extends AbstractAuroraConfigTest {

  static AuroraDeploymentSpec createDeploymentSpec(Map<String, String> auroraConfigJson, ApplicationId applicationId) {

    AuroraConfig auroraConfig = createAuroraConfig(auroraConfigJson)
    AuroraDeploymentSpecBuilderKt.createAuroraDeploymentSpec(auroraConfig, applicationId, [], [:])
  }

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
    "hasAccess": "APP_PaaS_utv"
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
}
