package no.skatteetaten.aurora.boober.controller.v1

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

import spock.lang.Specification

class RequestHelperTest extends Specification {

  private MockHttpServletRequest request

  void setup() {
    request = new MockHttpServletRequest()
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request))
  }

  def "Get ref-name from request given ref input"() {
    when:
      request.addParameter("reference", "abc123")
      def refName = RequestHelper.getRefNameFromRequest()

    then:
      refName == 'abc123'
  }

  def "Get ref-name from request given request header"() {
    given:
      request.addHeader('Ref-Name', 'header')

    when:
      def refName = RequestHelper.getRefNameFromRequest()

    then:
      refName == 'header'
  }


  def "Get ref-name given no ref input"() {
    when:
      def refName = RequestHelper.getRefNameFromRequest()

    then:
      refName == 'master'
  }
}
