package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.model.Result
import no.skatteetaten.aurora.boober.model.TemplateType
import spock.lang.Specification

class ServiceTest extends Specification {

  File configDir

  ObjectMapper mapper = new Configuration().mapper()

  ConfigService service = new ConfigService(mapper)

  def setup() {

    configDir = new File(ServiceTest.getResource("/samples/config").path)
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

  private Map<String, JsonNode> collectFilesToMap(String... fileNames) {
    return fileNames.collectEntries { [(it), mapper.readTree(new File(configDir, it))] }
  }
}
