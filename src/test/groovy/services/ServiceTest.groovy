package services

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

import no.skatteetaten.aurora.boober.ConfigService
import no.skatteetaten.aurora.boober.Result
import spock.lang.Specification

class ServiceTest extends Specification {

  File configDir
  ConfigService service

  ObjectMapper mapper = new ObjectMapper()

  def setup() {
    mapper.registerModule(new KotlinModule()).setSerializationInclusion(JsonInclude.Include.NON_NULL)
    service = new ConfigService(mapper)

    GroovyClassLoader classLoader = new GroovyClassLoader(this.class.getClassLoader())
    configDir = new File(classLoader.getResource("samples/config").path)
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
