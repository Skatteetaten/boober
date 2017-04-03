package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getQaEbsUsersSampleFiles
import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.jsonToMap

import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraDeploy
import no.skatteetaten.aurora.boober.model.AuroraDeploymentConfig
import no.skatteetaten.aurora.boober.model.TemplateType
import spock.lang.Specification

class AuroraConfigParserServiceTest extends Specification {

  public static final String ENV_NAME = "booberdev"
  public static final String APP_NAME = "verify-ebs-users"

  AuroraConfigParserService service = new AuroraConfigParserService()

  def "Should fail due to missing config file"() {

    given:
      Map<String, Map<String, Object>> files = getQaEbsUsersSampleFiles()
      files.remove("${APP_NAME}.json" as String)
      def auroraConfig = new AuroraConfig(files)

    when:
      service.createAuroraDcFromAuroraConfig(auroraConfig, ENV_NAME, APP_NAME)

    then:
      thrown(IllegalArgumentException)
  }

  def "Should successfully merge all config files"() {

    given:
      Map<String, Map<String, Object>> files = getQaEbsUsersSampleFiles()
      def auroraConfig = new AuroraConfig(files)

    when:
      AuroraDeploymentConfig auroraDc = service.createAuroraDcFromAuroraConfig(auroraConfig, ENV_NAME, APP_NAME)

    then:
      with(auroraDc) {
        namespace == "aos-${ENV_NAME}"
        affiliation == "aos"
        name == APP_NAME
        cluster == "utv"
        replicas == 1
        type == TemplateType.development
        groups == "APP_PaaS_drift APP_PaaS_utv"
      }

      with(auroraDc.deployDescriptor as AuroraDeploy) {
        version == "1.0.3-SNAPSHOT"
        groupId == "ske.admin.lisens"
        artifactId == "verify-ebs-users"

        prometheus.port == 8080
        splunkIndex == "openshift-test"
      }
  }

  def "Should override name property in 'app'.json with name in 'env'/'app'.json"() {

    given:
      Map<String, Map<String, Object>> files = getQaEbsUsersSampleFiles()

      def envAppOverride = """
        {
          "name": "awesome-app"
        }
      """

      files.put("${ENV_NAME}/${APP_NAME}.json" as String, jsonToMap(envAppOverride))

      def auroraConfig = new AuroraConfig(files)

    when:
      AuroraDeploymentConfig auroraDc = service.createAuroraDcFromAuroraConfig(auroraConfig, ENV_NAME, APP_NAME)

    then:
      auroraDc.name == "awesome-app"
  }

  def "Should throw ValidationException due to missing required properties"() {

    given: "AuroraConfig without build properties"
      Map<String, Map<String, Object>> files = getQaEbsUsersSampleFiles()

      def appOverride = """
        {
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

      files.put("${APP_NAME}.json" as String, jsonToMap(appOverride))
      files.put("${ENV_NAME}/${APP_NAME}.json" as String, [:])

      def auroraConfig = new AuroraConfig(files)

    when:
      service.createAuroraDcFromAuroraConfig(auroraConfig, ENV_NAME, APP_NAME)


    then:
      def ex = thrown(ValidationException)
      ex.errors.size() == 3
  }

}

