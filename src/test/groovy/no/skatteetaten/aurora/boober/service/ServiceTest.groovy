package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.model.ConfigService
import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.model.Result
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
      result.error != null
  }

  def "Should successfully merge all config files"() {
    given:
      def files = collectFilesToMap("about.json", "referanse.json", "utv/about.json", "utv/referanse.json")

    when:
      Result result = service.createBooberResult("utv", "referanse", files)

    then:
      result.error == null
      result.config.name == "refapp"
      result.config.build.version == "1"
  }

  private Map<String, JsonNode> collectFilesToMap(String... fileNames) {
    return fileNames.collectEntries { [(it), mapper.readTree(new File(configDir, it))] }
  }
}
