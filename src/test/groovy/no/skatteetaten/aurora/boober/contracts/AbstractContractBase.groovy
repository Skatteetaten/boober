package no.skatteetaten.aurora.boober.contracts

import org.springframework.test.web.servlet.setup.MockMvcBuilders

import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath

import io.restassured.module.mockmvc.RestAssuredMockMvc
import spock.lang.Specification

abstract class AbstractContractBase extends Specification {
  private DocumentContext json

  void loadResponseJson(String baseName, String responseName) {
    def fileName = "/contracts/${baseName}/${responseName}-response.json"
    InputStream resource = getClass().getResourceAsStream(fileName)
    if (resource == null) {
      throw new IllegalArgumentException("Unable to read the file ${fileName}")
    }

    json = JsonPath.parse(resource.text)
  }

  void loadResponseJson(String baseName) {
    loadResponseJson(baseName, baseName)
  }

  String jsonPath(String jsonPath) {
    if (json == null) {
      throw new IllegalStateException("JsonPath not initialized, call loadResponseJson(baseName)")
    }

    return json.read(jsonPath)
  }

  def setupMockMvc(Object controller) {
    RestAssuredMockMvc.standaloneSetup(MockMvcBuilders.standaloneSetup(controller))
  }

}
