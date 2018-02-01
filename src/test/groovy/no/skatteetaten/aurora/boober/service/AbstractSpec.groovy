package no.skatteetaten.aurora.boober.service

import spock.lang.Specification

abstract class AbstractSpec extends Specification {

  String loadResource(String resourceName) {
    def folder = this.getClass().simpleName
    loadResource(folder, resourceName)
  }

  String loadResource(String folder, String resourceName) {
    def resourcePath = "${folder}/$resourceName"

    def path = "src/test/resources/" +this.getClass().package.getName().replace(".", "/") + "/$resourcePath"
    
    this.getClass().getResource(resourcePath)?.text ?: { throw new IllegalArgumentException("No such resource $path")}()
  }
}
