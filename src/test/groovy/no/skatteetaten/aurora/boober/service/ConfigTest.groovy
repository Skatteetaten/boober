package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.mapper.v1.AuroraDeploymentSpecBuilderKt
import no.skatteetaten.aurora.boober.model.ApplicationId
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpec
import spock.lang.Specification

class ConfigTest extends Specification {

  def A() {

    given:

      def auroraConfigJson = [
          "about.json"        : '''{}''',
          "utv/about.json"    : '''{}''',
          "reference.json"    : '''{}''',
          "utv/reference.json": '''{}'''
      ]

      def objectMapper = new ObjectMapper()
      def auroraConfigFiles = auroraConfigJson.collect { name, contents ->
        new AuroraConfigFile(name, objectMapper.readValue(contents, JsonNode), false, null)
      }
      def auroraConfig = new AuroraConfig(auroraConfigFiles, "paas")
      AuroraDeploymentSpec deploymentSpec = AuroraDeploymentSpecBuilderKt.
          createAuroraDeploymentSpec(new ApplicationId("utv", "reference"), auroraConfig, "", [], [:])


    when:
      println deploymentSpec.name

    then:
      true
  }
}
