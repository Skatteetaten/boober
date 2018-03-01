package no.skatteetaten.aurora.boober.service

import static no.skatteetaten.aurora.boober.service.OpenShiftObjectLabelService.toOpenShiftSafeLabel

import spock.lang.Specification

class OpenShiftObjectLabelServiceTest extends Specification {

  def "Truncates labels when too long"() {

    expect:
      toOpenShiftSafeLabel(label) == expectedLabel

    where:
      label                                                                             | expectedLabel
      "feature-SAF-4831-18-DEV-b1.5.3-flange-8.152.18"                                  | "feature-SAF-4831-18-DEV-b1.5.3-flange-8.152.18"
      "feature-SAF-4831-opprette-og-vise-saksinfo-18-DEV-b1.5.3-flange-8.152.18"        | "AF-4831-opprette-og-vise-saksinfo-18-DEV-b1.5.3-flange-8.152.18"
      "feature-SAF-4831-opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152.18" | "opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152.18"
      "feature-SAF-4831----opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152" | "opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152"
      "feature-SAF-4831---------------------------------------------------------------" | ""
  }
}
