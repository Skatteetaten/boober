package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.mapper.AuroraConfigFields
import no.skatteetaten.aurora.boober.mapper.v1.AuroraRouteMapperV1
import no.skatteetaten.aurora.boober.mapper.v1.AuroraVolumeMapperV1
import no.skatteetaten.aurora.boober.model.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfigHelperKt

class AuroraConfigFieldTest extends AbstractAuroraConfigTest {

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

  def "Should throw exception when annotation has wrong separator"() {
    given:
      def auroraConfigJson = defaultAuroraConfig()
      auroraConfigJson["utv/aos-simple.json"] = '''{
  "route": {
    "console": {
      "annotations": {
        "haproxy.router.openshift.io/timeout": "600s"
      }
    }
  }
}
'''
      def auroraConfig = createAuroraConfig(auroraConfigJson)

    when:
      def routeMapper = new AuroraRouteMapperV1(auroraConfig.files, "console")
      def fields = AuroraConfigFields.create(routeMapper.handlers.toSet(), auroraConfig.files, [:])
      routeMapper.getRoute(fields)

    then:
      def e = thrown AuroraDeploymentSpecValidationException
      e.message == '''Annotation haproxy.router.openshift.io/timeout cannot contain '/'. Use '|' instead.'''
  }
}


