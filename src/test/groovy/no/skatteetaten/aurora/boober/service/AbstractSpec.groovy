package no.skatteetaten.aurora.boober.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import no.skatteetaten.aurora.boober.mapper.platform.ApplicationPlatformHandler
import no.skatteetaten.aurora.boober.mapper.platform.JavaPlatformHandler
import no.skatteetaten.aurora.boober.mapper.platform.WebPlatformHandler
import spock.lang.Specification

abstract class AbstractSpec extends Specification {

  def setup() {
    Map<String, ApplicationPlatformHandler> handlers = ["java": new JavaPlatformHandler(), "web": new WebPlatformHandler()]
    AuroraDeploymentSpecService.APPLICATION_PLATFORM_HANDLERS = handlers
  }

  byte[] loadByteResource(String resourceName) {
    def folder = this.getClass().simpleName
    loadUrl(folder, resourceName).bytes
  }

  String loadResource(String resourceName) {
    def folder = this.getClass().simpleName
    loadUrl(folder, resourceName).text
  }

  String loadResource(String folder, String resourceName) {
    return loadUrl(folder, resourceName).text
  }

  URL loadUrl(String folder, String resourceName) {
    def resourcePath = "${folder}/$resourceName"

    def path = "src/test/resources/" + this.getClass().getName().replace(".", "/") + "/$resourcePath"

    this.getClass().getResource(resourcePath) ?:
        { throw new IllegalArgumentException("No such resource $path") }()
  }

  JsonNode loadJsonResource(String resourceName) {

    new ObjectMapper().readValue(loadResource(resourceName), JsonNode)
  }
}
