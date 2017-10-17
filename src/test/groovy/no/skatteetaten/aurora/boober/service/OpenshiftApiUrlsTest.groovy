package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.service.openshift.OpenShiftApiUrls
import spock.lang.Specification
import spock.lang.Unroll

class OpenshiftApiUrlsTest extends Specification {

  String baseUrl = ""

  @Unroll
  def "Should create correct create url for #kind"() {

    given:
      def result = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, namespace, name)

    expect:
      result.create == url

    where:
      kind                | name  | namespace | url
      "buildrequest"      | "foo" | "bar"     | "/oapi/v1/namespaces/bar/buildconfigs/foo/instantiate"
      "deploymentrequest" | "foo" | "bar"     | "/oapi/v1/namespaces/bar/deploymentconfigs/foo/instantiate"
      "user"              | "foo" | null      | "/oapi/v1/users"
      "processedtemplate" | "foo" | "bar"     | "/oapi/v1/namespaces/bar/processedtemplates"

  }

  @Unroll
  def "Should create correct get url for #kind"() {

    given:
      def url = OpenShiftApiUrls.createOpenShiftApiUrls(baseUrl, kind, namespace, name)

    expect:

      url.get == result


    where:
      kind                | name  | namespace | result
      "buildrequest"      | "foo" | "bar"     | null
      "deploymentrequest" | "foo" | "bar"     | null
      "user"              | "foo" | null      | "/oapi/v1/users/foo"
      "project"           | "foo" | null      | "/oapi/v1/projects/foo"
      "service"           | "foo" | "bar"     | "/api/v1/namespaces/bar/services/foo"

  }
}
