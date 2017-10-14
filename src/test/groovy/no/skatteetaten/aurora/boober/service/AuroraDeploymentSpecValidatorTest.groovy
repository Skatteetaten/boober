package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient


class AuroraDeploymentSpecValidatorTest extends AbstractAuroraDeploymentSpecTest {

    def auroraConfigJson = defaultAuroraConfig()

    def openShiftClient = Mock(OpenShiftClient)

    def specValidator = new AuroraDeploymentSpecValidator(openShiftClient)


    def "Fails when admin groups is empty"() {
      given:
        auroraConfigJson["utv/aos-simple.json"] = '''{ "permissions": { "admin": { "groups": "" } } }'''
        AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

      when:
        specValidator.assertIsValid(deploymentSpec)

      then:
        thrown(AuroraDeploymentSpecValidationException)
    }


    def "Fails when admin groups does not exist"() {
      given:
        auroraConfigJson["utv/aos-simple.json"] = '''{ "permissions": { "admin": { "groups": "APP_PaaS_utv" } } }'''
        openShiftClient.isValidGroup("APP_PaaS_utv") >> false
        AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

      when:
        specValidator.assertIsValid(deploymentSpec)

      then:
        thrown(AuroraDeploymentSpecValidationException)
    }
}
