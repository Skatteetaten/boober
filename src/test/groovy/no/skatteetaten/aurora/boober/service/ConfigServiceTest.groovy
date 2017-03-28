package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.utils.SampleFilesCollector.getUtvReferanseSampleFiles

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.model.TemplateType
import spock.lang.Ignore
import spock.lang.Specification

@Ignore
class ConfigServiceTest extends Specification {

/*
  ObjectMapper mapper = new Configuration().mapper()
  ConfigService service = new ConfigService(mapper)

  def "Should fail due to missing config file"() {

    given:
      Map<String, JsonNode> files = getUtvReferanseSampleFiles()
      files.remove("referanse.json")

    when:
      service.createConfigFromAocConfigFiles("utv", "referanse", files)

    then:
      thrown(OpenShiftException)
  }

  def "Should successfully merge all config files"() {

    given:
      Map<String, JsonNode> files = getUtvReferanseSampleFiles()

    when:
      Result result = service.createConfigFromAocConfigFiles("utv", "referanse", files)

    then:
      result.errors.isEmpty()

      with(result.getConfig()) {
        affiliation == "aot"
        name == "refapp"
        cluster == "utv"
        replicas == 3
        type == TemplateType.deploy
        groups == "APP_PaaS_drift APP_PaaS_utv"
        flags == ["rolling", "route", "cert"]
        config == ["SERVER_URL": "http://localhost:8080"]

        build.version == "1"
        build.groupId == "ske.aurora.openshift.referanse"
        build.artifactId == "openshift-referanse-springboot-server"

        deploy.prometheusPort == 8081
        deploy.managementPath == ":8081/actuator"
        deploy.database == "referanseapp"
        deploy.splunkIndex == "openshift-test"
      }
  }

  def "Should override name property in 'app'.json with name in 'env'/'app'.json"() {

    given:
      Map<String, JsonNode> files = getUtvReferanseSampleFiles()

      def envAppOverride = """
        {
          "name": "Awesome App"
        }
      """

      files.put("utv/referanse.json", mapper.readTree(envAppOverride))

    when:
      Result result = service.createConfigFromAocConfigFiles("utv", "referanse", files)

    then:
      result.getConfig().name == "Awesome App"

  }

  def "Should fail due to missing required property"() {

    given:
      Map<String, JsonNode> files = getUtvReferanseSampleFiles()

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

      files.put("referanse.json", mapper.readTree(appOverride))
      files.put("utv/referanse.json", mapper.readTree("{}"))

    when:
      Result result = service.createConfigFromAocConfigFiles("utv", "referanse", files)

    then:
      result.errors[0] == "build is required"
  }

  def "Should fail due to missing required nested property"() {

    given:
      Map<String, JsonNode> files = getUtvReferanseSampleFiles()

      def consoleOverride = """
        {
          "name" : "console",
          "replicas" : 3,
          "flags" : ["rolling", "route", "cert" ],
            "build" : {
            "GROUP_ID":"ske.aurora.openshift.referanse",
            "ARTIFACT_ID":"openshift-referanse-springboot-server"
          },
          "deploy" : {
            "PROMETHEUS_ENABLED" : true,
            "PROMETHEUS_PORT" : "8081",
            "MANAGEMENT_PATH": ":8081/actuator",
            "DATABASE": "referanseapp"
          }
        }
      """

      files.put("referanse.json", mapper.readTree(consoleOverride))
      files.put("utv/referanse.json", mapper.readTree("{}"))

    when:
      Result result = service.createConfigFromAocConfigFiles("utv", "referanse", files)

    then:
      result.errors[0] == "build.VERSION is required"
  }*/
}
