package no.skatteetaten.aurora.boober.controller

import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint

import org.junit.Rule
import org.springframework.restdocs.JUnitRestDocumentation
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler
import org.springframework.restdocs.snippet.Snippet
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders

import spock.lang.Specification

abstract class AbstractControllerTest extends Specification {

  @Rule
  JUnitRestDocumentation jUnitRestDocumentation = new JUnitRestDocumentation("build/docs/generated-snippets")

  MockMvc mockMvc

  def setup() {

    def controllers = []
    controllers.addAll(controllersUnderTest)
    mockMvc = MockMvcBuilders.standaloneSetup(controllers.toArray())
        .setControllerAdvice(new ErrorHandler())
        .apply(documentationConfiguration(jUnitRestDocumentation))
        .build()

  }

  protected static RestDocumentationResultHandler prettyDoc(String identifier, Snippet... snippets) {
    document(identifier, preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint()), snippets)
  }

  protected abstract List<Object> getControllersUnderTest()
}
