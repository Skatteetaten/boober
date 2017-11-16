package no.skatteetaten.aurora.boober.service

import spock.lang.Specification

abstract class AbstractSpec extends Specification {

  String loadResource(String resourceName) {
    def folder = this.getClass().simpleName
    loadResource(folder, resourceName)
  }

  String loadResource(folder, String resourceName) {
    def resourcePath = "${folder}/$resourceName"
    this.getClass().getResource(resourcePath)?.text ?: { throw new IllegalArgumentException("No such resource $resourcePath")}()
  }
}
