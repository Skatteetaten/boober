package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient


class AuroraDeploymentSpecValidatorTest extends AbstractAuroraDeploymentSpecTest {

    def auroraConfigJson = defaultAuroraConfig()

    def openShiftClient = Mock(OpenShiftClient)

    def specValidator = new AuroraDeploymentSpecValidator(openShiftClient)


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
        auroraConfigJson["aos-simple.json"] = '''{ "type": "template", "name": "aos-simple", "template": "aurora-deploy-3.0" }'''
        openShiftClient.isValidGroup("APP_PaaS_utv") >> true
        openShiftClient.templateExist("aurora-deploy-3.0") >> false
        AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

      when:
        specValidator.assertIsValid(deploymentSpec)

      then:
        thrown(AuroraDeploymentSpecValidationException)
    }
}
