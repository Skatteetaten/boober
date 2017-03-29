package no.skatteetaten.aurora.boober.controller

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

import org.springframework.http.MediaType

import groovy.json.JsonSlurper
import no.skatteetaten.aurora.boober.Configuration
import no.skatteetaten.aurora.boober.service.AuroraConfigParserService
import no.skatteetaten.aurora.boober.service.OpenShiftClient
import no.skatteetaten.aurora.boober.service.OpenShiftService
import no.skatteetaten.aurora.boober.service.SetupService
import no.skatteetaten.aurora.boober.service.ValidationService
import no.skatteetaten.aurora.boober.utils.SampleFilesCollector

class SetupControllerTest extends AbstractControllerTest {

  def mapper = new Configuration().mapper()

  def validationService = new ValidationService()
  def auroraParser = new AuroraConfigParserService(validationService)

  def setupService = new SetupService(auroraParser, Stub(OpenShiftService), Stub(OpenShiftClient))
  def controller = new SetupController(setupService)

  def "Should fail"() {
    given:
      def files = SampleFilesCollector.utvReferanseSampleFiles
      files.put("about.json", mapper.readTree("{}"))
      SetupCommand cmd = new SetupCommand("aos", "utv", "referanse", files, [:])
      def json = mapper.writeValueAsString(cmd)

    when:
      def response = mockMvc
          .perform(put('/setup')
          .header("Authentication", "Bearer test")
          .contentType(MediaType.APPLICATION_JSON)
          .content(json))
      def body = new JsonSlurper().parseText(response.andReturn().response.getContentAsString())

    then:
      body.errors.size() == 3
      body.errors.affiliation != null
      body.errors.namespace != null
      body.errors.type != null
      response.andExpect(status().is4xxClientError())
  }

  @Override
  protected List<Object> getControllersUnderTest() {
    return [controller]
  }
}
