package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.mapper.AuroraConfigField
import no.skatteetaten.aurora.boober.mapper.AuroraConfigFieldSource
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
import org.junit.jupiter.params.provider.EnumSource

class AuroraConfigFieldTypeTest {

    val mapper = ObjectMapper()
    val value = mapper.readTree("[]")
    val substitutor = StringSubstitutor()

    @ParameterizedTest
    @EnumSource(Arguments::class)
    fun `should determine type of field`(it: Arguments) {

        val acf = AuroraConfigField(
            setOf(
                AuroraConfigFieldSource(
                    AuroraConfigFile(it.field.fileName, "", it.field.isOverride, it.field.isDefault),
                    value,
                    false
                )
            ), substitutor
        )

        assertThat(acf.fileType).isEqualTo(it.field.type)
    }

    data class AuroraConfigFieldTestData(
        val fileName: String,
        val isOverride: Boolean,
        val isDefault: Boolean,
        val type: AuroraConfigFileType
    )

    enum class Arguments(val field: AuroraConfigFieldTestData) {
        FILENAME(
            AuroraConfigFieldTestData(
                "fileName",
                false,
                true,
                DEFAULT
            )
        ),
        ABOUT(
            AuroraConfigFieldTestData(
                "about.json",
                false,
                false,
                GLOBAL
            )
        ),
        ABOUT_OVERRIDE(
            AuroraConfigFieldTestData(
                "about.json",
                true,
                false,
                GLOBAL_OVERRIDE
            )
        ),
        REFERENCE(
            AuroraConfigFieldTestData(
                "reference.json",
                false,
                false,
                BASE
            )
        ),
        REFERENCE_OVERRIDE(
            AuroraConfigFieldTestData(
                "reference.json",
                true,
                false,
                BASE_OVERRIDE
            )
        ),
        UTV_ABOUT(
            AuroraConfigFieldTestData(
                "utv/about.json",
                false,
                false,
                ENV
            )
        ),
        UTV_ABOUT_OVERRIDE(
            AuroraConfigFieldTestData(
                "utv/about.json",
                true,
                false,
                ENV_OVERRIDE
            )
        ),
        UTV_ABOUT_TEMPLATE(
            AuroraConfigFieldTestData(
                "utv/about-template.json",
                false,
                false,
                ENV
            )
        ),
        UTV_ABOUT_TEMPLATE_OVERRIDE(
            AuroraConfigFieldTestData(
                "utv/about-template.json",
                true,
                false,
                ENV_OVERRIDE
            )
        ),
        UTV_REFERENCE(
            AuroraConfigFieldTestData(
                "utv/reference.json",
                false,
                false,
                APP
            )
        ),
        UTV_REFERENCE_OVERRIDE(
            AuroraConfigFieldTestData(
                "utv/reference.json",
                true,
                false,
                APP_OVERRIDE
            )
        )
    }
}
