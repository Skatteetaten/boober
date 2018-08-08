package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.mapper.v1.AuroraVolumeMapperV1
import no.skatteetaten.aurora.boober.model.AbstractAuroraDeploymentSpecTest

class AuroraConfigFieldTest extends AbstractAuroraDeploymentSpecTest {

  def "Should generate correct config extractors"() {
    given:
      def auroraConfigJson = defaultAuroraConfig()
      auroraConfigJson["utv/aos-simple.json"] = '''{
  "type": "deploy",
  "config": {
    "foo": "baaaar",
    "bar": "bar",
    "1": {
      "bar": "baz",
      "foo": "baz"
    }
  }
}'''
      def auroraConfig = createAuroraConfig(auroraConfigJson)
      def files = auroraConfig.files
    when:
      def mapper = new AuroraVolumeMapperV1(files)

    then:
      mapper.configHandlers.collect { it.path } == ["/config/foo", "/config/bar", "/config/1/bar", "/config/1/foo"]
  }
}


