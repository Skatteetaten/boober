package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient

class OpenShiftObjectGeneratorConfigMapTest extends AbstractAuroraDeploymentSpecTest {

  OpenShiftObjectGenerator objectGenerator = createObjectGenerator()

  def "Verify properties entries contains a line for each property"() {

    given:

      def auroraConfigJson = [
          "about.json"         : DEFAULT_ABOUT,
          "utv/about.json"     : DEFAULT_UTV_ABOUT,
          "aos-simple.json"    : AOS_SIMPLE_JSON,
          "utv/aos-simple.json": '''{
  "config": {
    "OPPSLAGSTJENESTE_DELEGERING" : "[ { uri: \\"http://tsl0part-fk1-s-adm01:20000/registry\\", urn: [\\"urn:skatteetaten:part:identifikasjon:partsidentifikasjon:root\\"], segment: \\"part\\" }, { uri: \\"http://tsl0part-fk1-s-adm01:20000/registry\\", urn: [\\"urn:skatteetaten:part:partsregister:feed:*\\"], segment: \\"part\\" } , { uri: \\"http://tsl0part-fk1-s-adm01:20000/registry\\", urn: [\\"urn:skatteetaten:part:partsregister:hendelselager:*\\"], segment: \\"part\\" } , { uri: \\"http://tsl0part-fk1-s-adm01:20000/registry\\", urn: [\\"no:skatteetaten:sikkerhet:tilgangskontroll:ats:v1\\"], segment: \\"part\\" } ]",
    "UTSTED_SAML_URL" : "https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v2/utstedSaml",
    "VALIDER_SAML_URL" : "https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v2/validerSaml",
    "1": {
      "OPPSLAGSTJENESTE_DELEGERING" : "[ { uri: \\"http://tsl0part-fk1-s-adm02:20000/registry\\", urn: [\\"urn:skatteetaten:part:identifikasjon:partsidentifikasjon:root\\"], segment: \\"part\\" } ]",
      "VALIDER_SAML_URL" : "https://int-at2.skead.no:13110/felles/sikkerhet/stsSikkerhet/v2/validerSaml"
    }
  }
}'''
      ]

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, aid("utv", "aos-simple"))

    when:
      def jsonMounts = objectGenerator.generateMount(deploymentSpec, "deploy-id")

    then:
      jsonMounts.size() == 1
      JsonNode mount = jsonMounts.first()

    and: "the latest.properties property contains a string with each property on a separate line"
      def latestProperties = mount.get('data').get('latest.properties').textValue()

      assertFileHasLinesWithProperties(latestProperties,
          ["OPPSLAGSTJENESTE_DELEGERING", "UTSTED_SAML_URL", "VALIDER_SAML_URL"])

    and: "the 1.properties property contains a string with each property on a separate line"
      def propertiesFile = mount.get('data').get('1.properties').textValue()
      assertFileHasLinesWithProperties(propertiesFile, ["OPPSLAGSTJENESTE_DELEGERING", "VALIDER_SAML_URL"])
  }

  void assertFileHasLinesWithProperties(String latestProperties, List<String> propertyNames) {
    // The following statement will produce a list of pairs of property name and a line from the latest.properties
    def lineProperties = [propertyNames, latestProperties.readLines()].transpose()
    lineProperties.each { String propertyName, String propertyLine ->
      assert propertyLine.startsWith(propertyName)
    }
  }

  OpenShiftObjectGenerator createObjectGenerator() {
    def ve = new Configuration().velocity()
    def objectMapper = new Configuration().mapper()
    def userDetailsProvider = Mock(UserDetailsProvider)
    userDetailsProvider.getAuthenticatedUser() >> new User("aurora", "token", "Aurora OpenShift")
    new OpenShiftObjectGenerator(
        userDetailsProvider, ve, objectMapper, Mock(OpenShiftTemplateProcessor), Mock(OpenShiftResourceClient))
  }
}
