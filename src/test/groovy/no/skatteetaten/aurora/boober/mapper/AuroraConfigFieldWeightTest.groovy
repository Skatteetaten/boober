package no.skatteetaten.aurora.boober.mapper

import org.apache.commons.text.StringSubstitutor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import spock.lang.Specification
import spock.lang.Unroll

class AuroraConfigFieldWeightTest extends Specification {

  def mapper = new ObjectMapper()
  JsonNode value = mapper.readTree("[]")
  def substitutor = new StringSubstitutor()

  @Unroll
  def "should determine weight of field=#fileName, default=#isDefault"() {

    given:

      def acf = new AuroraConfigField(
          [new AuroraConfigFieldSource(fileName, value, isDefault)] as Set,
          substitutor
      )

    when:
      def order = acf.weight()
    then:
      order == weight

    where:

      fileName                           | isDefault | weight
      "fileName"                         | true      | 0
      "about.json"                       | false     | 1    // aboutfil
      "about.json.override"              | false     | 2
      "reference.json"                   | false     | 3    // basefil
      "reference.json.override"          | false     | 4
      "utv/about.json"                   | false     | 5    // miljøfil
      "utv/about.json.override"          | false     | 6
      "utv/about-template.json"          | false     | 5
      "utv/about-template.json.override" | false     | 6
      "utv/reference.json"               | false     | 7    // applikasjonsfil
      "utv/reference.json.override"      | false     | 8
  }

}
