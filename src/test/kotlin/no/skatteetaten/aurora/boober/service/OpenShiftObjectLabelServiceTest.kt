package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class OpenShiftObjectLabelServiceTest {

    enum class LabelsTestData(val label: String, val expeected: String) {
        FULL("feature-SAF-4831-18-DEV-b1.5.3-flange-8.152.18", "feature-SAF-4831-18-DEV-b1.5.3-flange-8.152.18"),
        DEV(
            "feature-SAF-4831-opprette-og-vise-saksinfo-18-DEV-b1.5.3-flange-8.152.18",
            "AF-4831-opprette-og-vise-saksinfo-18-DEV-b1.5.3-flange-8.152.18"
        ),
        DEV_LONG(
            "feature-SAF-4831-opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152.18",
            "opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152.18"
        ),
        DEV_DASHES(
            "feature-SAF-4831----opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152",
            "opprette-og-vise-saksinformasjon-18-DEV-b1.5.3-flange-8.152"
        ),
        DASHES("feature-SAF-4831---------------------------------------------------------------", "")
    }

    @ParameterizedTest
    @EnumSource(LabelsTestData::class)
    fun `Truncates labels when too long`(labelData: LabelsTestData) {
        assertThat(OpenShiftObjectLabelService.toOpenShiftSafeLabel(labelData.label)).isEqualTo(labelData.expeected)
    }
}
