package no.skatteetaten.aurora.boober.model

import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentSpecBuilderKt
import no.skatteetaten.aurora.boober.service.AuroraConfigException

class AuroraDeploymentSpecBuilderTest extends AbstractAuroraConfigTest {

  static AuroraDeploymentSpec createDeploymentSpec(Map<String, String> auroraConfigJson, ApplicationId applicationId) {

    AuroraConfig auroraConfig = createAuroraConfig(auroraConfigJson)
    AuroraDeploymentSpecBuilderKt.createAuroraDeploymentSpec(auroraConfig, applicationId, "", [], [:])
  }

  def auroraConfigJson = defaultAuroraConfig()

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

  def "A"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ 
  "config": { 
    "BOOL": false,
    "INT": 42,
    "FLOAT": 4.2
  } 
}'''

    when:
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      deploymentSpec.fields.each { println it }
      true
    println deploymentSpec.fields["config/BOOL"].value
    println deploymentSpec.fields["config/INT"].value
    println deploymentSpec.fields["config/FLOAT"].value
  }
}
