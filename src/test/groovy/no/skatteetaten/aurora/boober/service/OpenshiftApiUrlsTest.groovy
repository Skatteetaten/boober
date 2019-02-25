package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import spock.lang.Specification
import spock.lang.Unroll

class OpenshiftApiUrlsTest extends Specification {

  @Unroll
  def "Should create correct url for #kind"() {

    given:
      def result = OpenShiftResourceClient.generateUrl(kind, namespace, name)

    expect:
      result == url

    where:
      kind                | name  | namespace | url
      "user"              | "foo" | null      | "/apis/user.openshift.io/v1/users/foo"
      "processedtemplate" | null  | "bar"     | "/oapi/v1/namespaces/bar/processedtemplates"
      "project"           | "foo" | null      | "/apis/project.openshift.io/v1/projects/foo"
      "service"           | "foo" | "bar"     | "/api/v1/namespaces/bar/services/foo"
  }

  def "Should get exception if generating url that requires namespace with no namespace"() {

    when:
      OpenShiftResourceClient.generateUrl("applicationdeployment", null, null)

    then:
      def e = thrown(IllegalArgumentException)
      e.message == "namespace required for resource kind applicationdeployment"

  }
}
