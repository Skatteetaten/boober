package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.model.TemplateType
import spock.lang.Specification

class ConfigServiceTest extends Specification {

  File configDir

  ObjectMapper mapper = new Configuration().mapper()

  ConfigService service = new ConfigService(mapper)

  def setup() {
    configDir = new File(ConfigServiceTest.getResource("/samples/config").path)
  }

  def "Should fail due to missing config file"() {

    given:
      def files = collectFilesToMap("about.json", "referanse.json", "utv/about.json")

    when:
      Result result = service.createBooberResult("utv", "referanse", files)

    then:
      result.errors.size() == 1
  }

  def "Should successfully merge all config files"() {

    given:
      def files = collectFilesToMap("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")

    when:
      Result result = service.createBooberResult("utv", "referanse", files)

    then:
      result.errors.isEmpty()

      with(result.config) {
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
      def files = collectFilesToMap("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")

      def envAppOverride = """
        {
          "name": "Awesome App"
        }
      """

      files.put("utv/referanse.json", mapper.readTree(envAppOverride))

    when:
      Result result = service.createBooberResult("utv", "referanse", files)

    then:
      result.config.name == "Awesome App"

  }

  def "Should fail due to missing property"() {

    given:
      def files = collectFilesToMap("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")

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
      Result result = service.createBooberResult("utv", "referanse", files)

    then:
      result.errors[0] == "build is required"
  }

  def "Should fail due to missing nested property"() {

    given:
      def files = collectFilesToMap("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")

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
      Result result = service.createBooberResult("utv", "referanse", files)

    then:
      result.errors[0] == "build.VERSION is required"
  }

  private Map<String, JsonNode> collectFilesToMap(String... fileNames) {
    return fileNames.collectEntries { [(it), mapper.readTree(new File(configDir, it))] }
  }
}
