package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getUtvReferanseSampleFiles
import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.jsonToMap

import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.TemplateType
import spock.lang.Specification

class AuroraConfigParserServiceTest extends Specification {

  def validationService = new ValidationService()
  AuroraConfigParserService service = new AuroraConfigParserService(validationService)

  def "Should fail due to missing config file"() {

    given:
      Map<String, Map<String, Object>> files = getUtvReferanseSampleFiles()
      files.remove("referanse.json")
      def auroraConfig = new AuroraConfig(files)

    when:
      service.createAuroraDcFromAuroraConfig(auroraConfig, "utv", "referanse")

    then:
      thrown(IllegalArgumentException)
  }

  def "Should successfully merge all config files"() {

    given:
      Map<String, Map<String, Object>> files = getUtvReferanseSampleFiles()
      def auroraConfig = new AuroraConfig(files)

    when:
      AuroraDeploymentConfig auroraDc = service.createAuroraDcFromAuroraConfig(auroraConfig, "utv", "referanse")

    then:
      with(auroraDc) {
        namespace == "aot-utv"
        affiliation == "aot"
        name == "refapp"
        cluster == "utv"
        replicas == 3
        type == TemplateType.deploy
        groups == "APP_PaaS_drift APP_PaaS_utv"
        config == ["SERVER_URL": "http://localhost:8080"]
      }

      with(auroraDc.deployDescriptor as AuroraDeploy) {
        version == "1"
        groupId == "ske.aurora.openshift.referanse"
        artifactId == "openshift-referanse-springboot-server"

        prometheus.port == 8081
        managementPath == ":8081/actuator"
        database == "referanseapp"
        splunkIndex == "openshift-test"
      }
  }

  def "Should override name property in 'app'.json with name in 'env'/'app'.json"() {

    given:
      Map<String, Map<String, Object>> files = getUtvReferanseSampleFiles()

      def envAppOverride = """
        {
          "name": "awesome-app"
        }
      """

      files.put("utv/referanse.json", jsonToMap(envAppOverride))

      def auroraConfig = new AuroraConfig(files)

    when:
      AuroraDeploymentConfig auroraDc = service.createAuroraDcFromAuroraConfig(auroraConfig, "utv", "referanse")

    then:
      auroraDc.name == "awesome-app"
  }

  def "Should throw ValidationException due to missing required properties"() {

    given: "AuroraConfig without build properties"
      Map<String, Map<String, Object>> files = getUtvReferanseSampleFiles()

      def appOverride = """
        {
          "name" : "console",
          "replicas" : 3,
          "flags" : ["rolling", "route", "cert" ],
          "deploy" : {
            "PROMETHEUS_ENABLED" : true,
            "PROMETHEUS_PORT" : "8081",
            "MANAGEMENT_PATH": ":8081/actuator",
            "DATABASE": "referanseapp"
          }
        }
      """

      files.put("referanse.json", jsonToMap(appOverride))
      files.put("utv/referanse.json", [:])

      def auroraConfig = new AuroraConfig(files)

    when:
      service.createAuroraDcFromAuroraConfig(auroraConfig, "utv", "referanse")


    then:
      def ex = thrown(ValidationException)
      ex.errors.size() == 3
  }

}

