package no.skatteetaten.aurora.boober.service

import spock.lang.Specification

class OpenShiftObjectLabelServiceTest extends Specification {

  def "Truncates labels when too long"() {

    when:
      def safeLabel = OpenShiftObjectLabelService.toOpenShiftSafeLabel(label)
    then:
      safeLabel ==~ /(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?/
      safeLabel.length() <= 63
      safeLabel == expectedLabel

    where:
      label                                                                             | expectedLabel
      "feature-SAF-4831-18-DEV-b1.5.3-flange-8.152.18"                                  |
          "feature-SAF-4831-18-DEV-b1.5.3-flange-8.152.18"
      "feature-SAF-4831-opprette-og-vise-saksinfo-18-DEV-b1.5.3-flange-8.152.18"        |
          "AF-4831-opprette-og-vise-saksinfo-18-DEV-b1.5.3-flange-8.152.18"
      "feature-SAF-4831-opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152.18" |
          "opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152.18"
      "feature-SAF-4831----opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152" |
          "opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152"
      "feature-SAF-4831---------------------------------------------------------------" | ""
  }
}
