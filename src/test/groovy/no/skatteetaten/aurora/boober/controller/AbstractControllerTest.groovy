package no.skatteetaten.aurora.boober.controller

import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint

import org.junit.Rule
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.restdocs.JUnitRestDocumentation
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler
import org.springframework.restdocs.snippet.Snippet
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

import spock.lang.Specification

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
abstract class AbstractControllerTest extends Specification {

  @Rule
  JUnitRestDocumentation jUnitRestDocumentation = new JUnitRestDocumentation("build/generated-snippets")

  MockMvc mockMvc

  @Autowired
  WebApplicationContext wac

  def setup() {

    mockMvc = MockMvcBuilders
        .webAppContextSetup(wac)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .apply(documentationConfiguration(jUnitRestDocumentation))
        .build()

  }

  protected static RestDocumentationResultHandler prettyDoc(String identifier, Snippet... snippets) {
    document(identifier, preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()), snippets)
  }
}
