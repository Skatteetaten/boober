package no.skatteetaten.aurora.boober.contracts

import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.setup.MockMvcBuilders

import com.fasterxml.jackson.databind.ObjectMapper
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath

import groovy.io.FileType
import io.restassured.module.mockmvc.RestAssuredMockMvc
import no.skatteetaten.aurora.boober.ObjectMapperConfigurer
import spock.lang.Specification

abstract class AbstractContractBase extends Specification {
  protected Map<String, DocumentContext> jsonResponses = [:]

  void loadJsonResponses(def baseObject) {
    def baseName = baseObject.getClass().getSimpleName().toLowerCase().replaceFirst('spec$', '')
    def files = loadFiles(baseName)
    populateResponses(files)
  }

  private static loadFiles(String baseName) {
    def folderName = "/contracts/${baseName}/responses"
    def resource = getClass().getResource(folderName)
    if (resource == null) {
      return []
    }

    def files = []
    new File(resource.toURI()).eachFileMatch(FileType.FILES, ~/.*\.json/, {
      files.add(it)
    })
    return files
  }

  private List populateResponses(List files) {
    files.each {
      def name = it.name.replace('.json', '')
      def json = JsonPath.parse(it)
      jsonResponses.put(name, json)
    }
  }

  def <T> T response(String responseName = jsonResponses.keySet().first(), String jsonPath, Class<T> type) {
    jsonResponses[responseName].read(jsonPath, type)
  }

  def <T> T responseObject(String responseName = jsonResponses.keySet().first(), Class<T> type) {
    def json = jsonResponses[responseName].jsonString()
    ObjectMapperConfigurer.configureObjectMapper(new ObjectMapper()).readValue(json, type)
  }

  def setupMockMvc(Object controller) {
    def objectMapper = ObjectMapperConfigurer.configureObjectMapper(new ObjectMapper())

    def converter = new MappingJackson2HttpMessageConverter()
    converter.setObjectMapper(objectMapper)

    RestAssuredMockMvc.standaloneSetup(MockMvcBuilders.standaloneSetup(controller).setMessageConverters(converter))
  }

}
