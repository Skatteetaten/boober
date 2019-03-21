package no.skatteetaten.aurora.boober.mapper

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.APP
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.APP_OVERRIDE
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.BASE
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.BASE_OVERRIDE
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.DEFAULT
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.ENV
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.ENV_OVERRIDE
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.GLOBAL
import no.skatteetaten.aurora.boober.model.AuroraConfigFileType.GLOBAL_OVERRIDE
import org.apache.commons.text.StringSubstitutor
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class AuroraConfigFieldTypeTest {

    val mapper = ObjectMapper()
    val value = mapper.readTree("[]")
    val substitutor = StringSubstitutor()

    @ParameterizedTest
    @MethodSource("arguments")
    fun `should determine type of field`(it: AuroraConfigFieldTestData) {

        val acf = AuroraConfigField(
            setOf(
                AuroraConfigFieldSource(
                    AuroraConfigFile(it.fileName, "", it.isOverride, it.isDefault),
                    value,
                    false
                )
            ), substitutor
        )

        assertThat(acf.fileType).isEqualTo(it.type)
    }

    data class AuroraConfigFieldTestData(
        val fileName: String,
        val isOverride: Boolean,
        val isDefault: Boolean,
        val type: AuroraConfigFileType
    )

    fun arguments() = listOf(
        AuroraConfigFieldTestData("fileName", false, true, DEFAULT),
        AuroraConfigFieldTestData("about.json", false, false, GLOBAL),
        AuroraConfigFieldTestData("about.json", true, false, GLOBAL_OVERRIDE),
        AuroraConfigFieldTestData("reference.json", false, false, BASE),
        AuroraConfigFieldTestData("reference.json", true, false, BASE_OVERRIDE),
        AuroraConfigFieldTestData("utv/about.json", false, false, ENV),
        AuroraConfigFieldTestData("utv/about.json", true, false, ENV_OVERRIDE),
        AuroraConfigFieldTestData("utv/about-template.json", false, false, ENV),
        AuroraConfigFieldTestData("utv/about-template.json", true, false, ENV_OVERRIDE),
        AuroraConfigFieldTestData("utv/reference.json", false, false, APP),
        AuroraConfigFieldTestData("utv/reference.json", true, false, APP_OVERRIDE)
    )
}
