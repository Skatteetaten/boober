package no.skatteetaten.aurora.boober.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import groovy.json.JsonOutput
import spock.lang.Specification
import spock.mock.DetachedMockFactory

@SpringBootTest(classes = [no.skatteetaten.aurora.boober.Configuration,
    ProcessService,
    Config])
class ProcessServiceTest extends Specification {

  public static final String APP_NAME = "verify-ebs-users"

  @Configuration
  static class Config {
    private DetachedMockFactory factory = new DetachedMockFactory()

    @Bean
    OpenshiftResourceClient client() {
      factory.Mock(OpenshiftResourceClient)
    }

  }

  @Autowired
  OpenshiftResourceClient client

  @Autowired
  ProcessService service

  @Autowired
  ObjectMapper mapper

  def "Should create objects from processing templateFile"() {
    given:
      def template = this.getClass().getResource("/openshift-objects/atomhopper.json")
      def templateResult = this.getClass().getResource("/openshift-objects/atomhopper-new.json")
      JsonNode jsonResult = mapper.readTree(templateResult)

      def adc = TestDataKt.generateProccessADC(mapper.readValue(template, Map.class))

    when:

      client.post("processedtemplate", null, adc.namespace, _) >>
          new ResponseEntity<JsonNode>(jsonResult, HttpStatus.OK)

      def generatedObjects = service.generateObjects(adc)

    then:
      generatedObjects.size() == 4



  }

  def compareJson(JsonNode jsonNode, String jsonString) {
    assert JsonOutput.prettyPrint(jsonNode.toString()) == JsonOutput.prettyPrint(jsonString)
    true
  }
}
