package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.service.openshift.OpenShiftApiUrls
import spock.lang.Specification
import spock.lang.Unroll

class OpenshiftApiUrlsTest extends Specification {

  String baseUrl = ""

  @Unroll
  def "Should create correct create url for #kind"() {

    given:
      def result = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, name, namespace)

    expect:
      result.create == url

    where:
      kind                | name  | namespace | url
      "buildrequest"      | "foo" | "bar"     | "/oapi/v1/namespaces/bar/buildconfigs/foo/instantiate"
      "deploymentrequest" | "foo" | "bar"     | "/oapi/v1/namespaces/bar/deploymentconfigs/foo/instantiate"
      "user"              | "foo" | null      | "/oapi/v1/users"
      "projectrequest"    | "foo" | null      | "/oapi/v1/projectrequests"

  }

  @Unroll
  def "Should create correct get url for #kind"() {

    given:
      def url = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, name, namespace)

    expect:

      url.get == result


    where:
      kind                | name  | namespace | result
      "buildrequest"      | "foo" | "bar"     | null
      "deploymentrequest" | "foo" | "bar"     | null
      "user"              | "foo" | null      | "/oapi/v1/users/foo"
      "projectrequest"    | "foo" | null      | "/oapi/v1/projects/foo"
      "service"           | "foo" | "bar"     | "/api/v1/namespaces/bar/services/foo"

  }
}
