package no.skatteetaten.aurora.boober.contracts

import org.springframework.test.web.servlet.setup.MockMvcBuilders

import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath

import groovy.io.FileType
import io.restassured.module.mockmvc.RestAssuredMockMvc
import spock.lang.Specification

abstract class AbstractContractBase extends Specification {
  protected Map<String, DocumentContext> jsonResponses = [:]

  void loadJsonResponses(String baseName) {
    def fileName = "/contracts/${baseName}"
    def files = loadFiles(fileName)
    populateResponses(files)
  }

  private static loadFiles(String fileName) {
    def resource = getClass().getResource(fileName)
    if (resource == null) {
      throw new IllegalArgumentException("Unable to read the file ${fileName}")
    }

    def files = []
    new File(resource.toURI()).eachFileMatch(FileType.FILES, ~/.*\.json/, {
      files.add(it)
    })
    return files
  }

  private List populateResponses(List files) {
    files.each {
      def name = it.name.replace('-response.json', '')
      def json = JsonPath.parse(it)
      jsonResponses.put(name, json)
    }
  }

  private def responseData(String responseName, String jsonPath) {
    return jsonResponses[responseName].read(jsonPath)
  }

  String responseString(String responseName = jsonResponses.keySet().first(), String jsonPath) {
    return responseData(responseName, jsonPath)
  }

  LinkedHashMap<String, String> responseObject(String responseName = jsonResponses.keySet().first(), String jsonPath) {
    return (LinkedHashMap) responseData(responseName, jsonPath)
  }

  def setupMockMvc(Object controller) {
    RestAssuredMockMvc.standaloneSetup(MockMvcBuilders.standaloneSetup(controller))
  }

}
