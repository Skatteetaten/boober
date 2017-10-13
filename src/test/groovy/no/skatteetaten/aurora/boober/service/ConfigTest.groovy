package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.model.ApplicationId.aid

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.controller.security.UserDetailsProvider
import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentSpecBuilderKt
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Specification

class ConfigTest extends Specification {

  def A() {

    given:

      def auroraConfigJson = [
          "about.json"        : '''{
  "schemaVersion": "v1",
  "permissions": {
    "admin": {
      "groups": "APP_PaaS_utv"
    }
  },
  "affiliation" : "aos"
}''',

          "utv/about.json"    : '''{
  "cluster": "utv"
}''',

          "reference.json"    : '''
{
  "certificate": true,
  "groupId": "ske.aurora.openshift",
  "artifactId": "aos-simple",
  "name": "reference",
  "version": "1.0.3",
  "route": true,
  "type": "deploy"
}''',

          "utv/reference.json": '''{
  "config": {
    "OPPSLAGSTJENESTE_DELEGERING" : "[ { uri: \\"http://tsl0part-fk1-s-adm01:20000/registry\\", urn: [\\"urn:skatteetaten:part:identifikasjon:partsidentifikasjon:root\\"], segment: \\"part\\" }, { uri: \\"http://tsl0part-fk1-s-adm01:20000/registry\\", urn: [\\"urn:skatteetaten:part:partsregister:feed:*\\"], segment: \\"part\\" } , { uri: \\"http://tsl0part-fk1-s-adm01:20000/registry\\", urn: [\\"urn:skatteetaten:part:partsregister:hendelselager:*\\"], segment: \\"part\\" } , { uri: \\"http://tsl0part-fk1-s-adm01:20000/registry\\", urn: [\\"no:skatteetaten:sikkerhet:tilgangskontroll:ats:v1\\"], segment: \\"part\\" } ]",
    "UTSTED_SAML_URL" : "https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v2/utstedSaml",
    "VALIDER_SAML_URL" : "https://int-at.skead.no:13110/felles/sikkerhet/stsSikkerhet/v2/validerSaml"
  }
}'''
      ]
      def ve = new Configuration().velocity()
      def objectMapper = new Configuration().mapper()
      def userDetailsProvider = Mock(UserDetailsProvider)
      userDetailsProvider.getAuthenticatedUser() >> new User("aurora", "token", "Aurora OpenShift")
      def objectGenerator = new OpenShiftObjectGenerator(
          userDetailsProvider, ve, objectMapper, Mock(OpenShiftTemplateProcessor), Mock(OpenShiftResourceClient))
      def applicationId = aid("utv", "reference")

      AuroraDeploymentSpec deploymentSpec = createDeploymentSpec(auroraConfigJson, applicationId)


    when:
      println deploymentSpec.name
      def mounts = objectGenerator.findMounts(deploymentSpec)
      def labels = objectGenerator.findLabels(deploymentSpec, "", deploymentSpec.name)

      def jsonMounts = objectGenerator.generateMount(mounts, labels)

    then:
      jsonMounts.forEach {
        println jsonMounts
      }
  }

  static AuroraDeploymentSpec createDeploymentSpec(Map<String, String> auroraConfigJson, ApplicationId applicationId) {

    def objectMapper = new ObjectMapper()
    def auroraConfigFiles = auroraConfigJson.collect { name, contents ->
      new AuroraConfigFile(name, objectMapper.readValue(contents, JsonNode), false, null)
    }
    def auroraConfig = new AuroraConfig(auroraConfigFiles, "aos")
    AuroraDeploymentSpecBuilderKt.createAuroraDeploymentSpec(applicationId, auroraConfig, "", [], [:])
  }
}
