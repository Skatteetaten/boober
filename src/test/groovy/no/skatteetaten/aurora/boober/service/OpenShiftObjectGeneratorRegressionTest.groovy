package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired

import com.fasterxml.jackson.databind.JsonNode

import no.skatteetaten.aurora.boober.model.AuroraPermissions
import no.skatteetaten.aurora.boober.model.Mount
import no.skatteetaten.aurora.boober.model.MountType

@DefaultOverride(auroraConfig = false)
class OpenShiftObjectGeneratorRegressionTest extends AbstractMockedOpenShiftSpecification {

  @Autowired
  OpenShiftObjectGenerator objectGenerator

  def "Serializes all config elements as string"() {
    given:
      def config = [
          OPPSLAGSTJENESTE_DELEGERING: '''[ { uri: "http://tsl0part-fk1-s-adm01:20000/registry", urn: ["urn:skatteetaten:part:identifikasjon:partsidentifikasjon:root"], segment: "part" }, { uri: "http://tsl0part-fk1-s-adm01:20000/registry", urn: ["urn:skatteetaten:part:partsregister:feed:*"], segment: "part" } , { uri: "http://tsl0part-fk1-s-adm01:20000/registry", urn: ["urn:skatteetaten:part:partsregister:hendelselager:*"], segment: "part" } , { uri: "http://tsl0part-fk1-s-adm01:20000/registry", urn: ["no:skatteetaten:sikkerhet:tilgangskontroll:ats:v1"], segment: "part" } ]''',
          BOOL_CONFIG                : true,
          INT_CONFIG                 : 1,
          FLOAT_CONFIG               : 1.101,
          STRING_CONFIG              : "Just an ordinary string",
          NULL_CONFIG                : null
      ]
    when:

      def mount = new Mount("", MountType.ConfigMap, "", "", false, config, new AuroraPermissions([], []))
      JsonNode mountJson = objectGenerator.generateMount([mount], [:]).first()

    then:
      config.forEach { key, value ->
        def valueNode = mountJson.get("data").get(key)
        assert valueNode.textual

        def jsonValue = valueNode.textValue()
        assert jsonValue == value != null ? "$value" : ""
      }
  }
}
