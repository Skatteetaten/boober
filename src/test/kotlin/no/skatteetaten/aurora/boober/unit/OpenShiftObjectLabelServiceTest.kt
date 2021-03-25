package no.skatteetaten.aurora.boober.unit

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.utils.normalizeLabels

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
        assertThat(
            mapOf("label" to labelData.label).normalizeLabels().values.first()
        ).isEqualTo(labelData.expeected)
    }
}
