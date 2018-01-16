package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec

class OpenShiftObjectGeneratorConfigMapTest extends AbstractOpenShiftObjectGeneratorTest {

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
    "UTSTED_SAML_URL" : "https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v1/utstedSaml",
    "VALIDER_SAML_URL" : "https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v1/validerSaml",
    "1": {
      "OPPSLAGSTJENESTE_DELEGERING" : "[ { uri: \\"http://tsl0part-fk1-s-adm02:20000/registry\\", urn: [\\"urn:skatteetaten:part:identifikasjon:partsidentifikasjon:root\\"], segment: \\"part\\" } ]",
      "VALIDER_SAML_URL" : "https://int-at2.skead.no:13110/felles/sikkerhet/stsSikkerhet/v1/validerSaml"
    }
  }
}'''
      ]

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, aid("utv", "aos-simple"))

    when:
      def jsonMounts = objectGenerator.generateMounts("deploy-id", deploymentSpec, null)

    then:
      jsonMounts.size() == 1
      JsonNode mount = jsonMounts.first()

    and: "there are env fields for all config elements in root"
      deploymentSpec.deploy.env.containsKey("OPPSLAGSTJENESTE_DELEGERING")
      deploymentSpec.deploy.env.containsKey("UTSTED_SAML_URL")
      deploymentSpec.deploy.env.containsKey("VALIDER_SAML_URL")

    and: "the 1.properties property contains a string with each property on a separate line"
      def propertiesFile = mount.get('data').get('1.properties').textValue()
      assertFileHasLinesWithProperties(propertiesFile, ["OPPSLAGSTJENESTE_DELEGERING", "VALIDER_SAML_URL"])
  }

  def "Renders non String configs properly"() {
    given:
      def auroraConfigJson = defaultAuroraConfig()
      auroraConfigJson["utv/aos-simple.json"] = '''{ 
  "config": {
    "STRING": "Hello", 
    "BOOL": false,
    "INT": 42,
    "FLOAT": 4.2,
    "ARRAY": [4.2, "STRING", true],
    "URL": "https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v1/validerSaml",
    "JSON_STRING": "{\\"key\\": \\"value\\"}"
  } 
}'''
    when:
      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

    then:
      def env = deploymentSpec.deploy.env
      env["STRING"] == "Hello"
      env["BOOL"] == "false"
      env["INT"] == "42"
      env["FLOAT"] == "4.2"
      env["ARRAY"] == '''[4.2,\\"STRING\\",true]'''
      env["JSON_STRING"] == '''{\\"key\\": \\"value\\"}'''
      env["URL"] == '''https:\\/\\/int-at.skead.no:13110\\/felles\\/sikkerhet\\/stsSikkerhet\\/v1\\/validerSaml'''
  }

  void assertFileHasLinesWithProperties(String latestProperties, List<String> propertyNames) {
    // The following statement will produce a list of pairs of property name and a line from the latest.properties
    def lineProperties = [propertyNames, latestProperties.readLines()].transpose()
    lineProperties.each { String propertyName, String propertyLine ->
      assert propertyLine.startsWith("$propertyName=")
      assert !propertyLine.startsWith("$propertyName=null")
    }
  }
}
