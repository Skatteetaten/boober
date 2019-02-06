package no.skatteetaten.aurora.boober.mapper

import static no.skatteetaten.aurora.boober.model.AuroraConfigFileType.APP
import static no.skatteetaten.aurora.boober.model.AuroraConfigFileType.APP_OVERRIDE
import static no.skatteetaten.aurora.boober.model.AuroraConfigFileType.BASE
import static no.skatteetaten.aurora.boober.model.AuroraConfigFileType.BASE_OVERRIDE
import static no.skatteetaten.aurora.boober.model.AuroraConfigFileType.DEFAULT
import static no.skatteetaten.aurora.boober.model.AuroraConfigFileType.ENV
import static no.skatteetaten.aurora.boober.model.AuroraConfigFileType.ENV_OVERRIDE
import static no.skatteetaten.aurora.boober.model.AuroraConfigFileType.GLOBAL
import static no.skatteetaten.aurora.boober.model.AuroraConfigFileType.GLOBAL_OVERRIDE

import org.apache.commons.text.StringSubstitutor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import spock.lang.Specification
import spock.lang.Unroll

class AuroraConfigFieldTypeTest extends Specification {

  def mapper = new ObjectMapper()
  JsonNode value = mapper.readTree("[]")
  def substitutor = new StringSubstitutor()

  @Unroll
  def "should determine type of field=#fileName, override=#isOverride, default=#isDefault"() {

    given:

      def acf = new AuroraConfigField(
          [new AuroraConfigFieldSource(new AuroraConfigFile(fileName, "", isOverride, isDefault), value, false)] as Set,
          substitutor
      )

    when:
      def exepectedType = acf.fileType
    then:
      exepectedType == type

    where:

      fileName                  | isOverride | isDefault | type
      "fileName"                | false      | true      | DEFAULT
      "about.json"              | false      | false     | GLOBAL
      "about.json"              | true       | false     | GLOBAL_OVERRIDE
      "reference.json"          | false      | false     | BASE
      "reference.json"          | true       | false     | BASE_OVERRIDE
      "utv/about.json"          | false      | false     | ENV
      "utv/about.json"          | true       | false     | ENV_OVERRIDE
      "utv/about-template.json" | false      | false     | ENV
      "utv/about-template.json" | true       | false     | ENV_OVERRIDE
      "utv/reference.json"      | false      | false     | APP
      "utv/reference.json"      | true       | false     | APP_OVERRIDE
  }

}
