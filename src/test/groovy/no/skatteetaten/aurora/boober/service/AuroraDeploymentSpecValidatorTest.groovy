package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient

class AuroraDeploymentSpecValidatorTest extends AbstractAuroraDeploymentSpecTest {

  def auroraConfigJson = defaultAuroraConfig()

  def udp = Mock(UserDetailsProvider)
  def openShiftClient = Mock(OpenShiftClient)

  def processor = new OpenShiftTemplateProcessor(udp, Mock(OpenShiftResourceClient), new ObjectMapper())
  def specValidator = new AuroraDeploymentSpecValidator(openShiftClient, processor)

  def mapper = new ObjectMapper()

  def setup() {
    udp.authenticatedUser >> new User("hero", "token", "Test User")
  }

  def "Fails when affiliation is too long"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "affiliation": "aaregistere" }'''

    when:
      createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      def e = thrown(AuroraConfigException)
      e.message ==
          "Config for application aos-simple in environment utv contains errors. Affiliation can only contain letters and must be no longer than 10 characters."
  }

  def "Fails when admin groups is empty"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "permissions": { "admin": "" } }'''
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      specValidator.assertIsValid(deploymentSpec)

    then:
      thrown(AuroraDeploymentSpecValidationException)
  }

  def "Fails when admin groups does not exist"() {
    given:
      auroraConfigJson["utv/aos-simple.json"] = '''{ "permissions": { "admin": "APP_PaaS_utv" } }'''
      openShiftClient.isValidGroup("APP_PaaS_utv") >> false
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    when:
      specValidator.assertIsValid(deploymentSpec)

    then:
      thrown(AuroraDeploymentSpecValidationException)
  }

  def "Fails when template does not exist"() {
    given:
      auroraConfigJson["aos-simple.json"] = '''{ "type": "template", "name": "aos-simple", "template": "atomhopper" }'''
      openShiftClient.isValidGroup("APP_PaaS_utv") >> true
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
      openShiftClient.isValidGroup("APP_PaaS_utv") >> true
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
          "Required template parameters [FEED_NAME, DATABASE] not set. Template does not contain parameter(s) [FOO]."
  }

  def "Fails when template does not contain required fields"() {
    given:
      auroraConfigJson["aos-simple.json"] = '''{ "type": "template", "name": "aos-simple", "template": "atomhopper" }'''
      openShiftClient.isValidGroup("APP_PaaS_utv") >> true
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
      e.message == "Required template parameters [FEED_NAME, DATABASE] not set."
  }
}
