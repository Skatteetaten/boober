package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.service.renderJsonForAuroraDeploymentSpecPointers
import no.skatteetaten.aurora.boober.service.renderSpecAsJson
import no.skatteetaten.aurora.boober.utils.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.utils.jsonMapper
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class AuroraDeploymentSpecRendererTest : AbstractAuroraConfigTest() {

    /* TODO: reimplement
    val auroraConfigJson = mutableMapOf(
        "about.json" to DEFAULT_ABOUT,
        "utv/about.json" to DEFAULT_UTV_ABOUT,
        "webleveranse.json" to WEB_LEVERANSE,
        "reference.json" to REFERENCE_WEBSEAL,
        "utv/reference.json" to """{ "webseal" : { "roles" : "foobar" }}""",
        "utv/webleveranse.json" to """{ "type": "development", "version": "1" }"""
    )
    val mapper = jsonMapper()

    enum class SpecJsonData(
        val env: String,
        val app: String,
        val default: Boolean
    ) {
        WEBLEVERANSE_JSON("utv", "webleveranse", false),
        WEBLEVERANSE_WITH_DEFAULTS_JSON("utv", "webleveranse", true),
        REFERENCE_WITH_DEFAULTS_JSON("utv", "reference", true),
        REFERENCE_JSON("utv", "reference", false)
    }

    @ParameterizedTest
    @EnumSource(SpecJsonData::class)
    fun `Should create a json of AuroraDeploymentSpec pointers`(test: SpecJsonData) {
        val aid = ApplicationDeploymentRef.adr(test.env, test.app)
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid).spec

        val renderedJson = renderSpecAsJson(deploymentSpec, test.default)
        val filename = getFilename(aid, test.default)
        val expected = loadResource(filename)

        val json = mapper.readTree(mapper.writeValueAsString(renderedJson))
        val expectedJson = mapper.readTree(expected)
        compareJson(expectedJson, json)
    }

    enum class SpecStringData(
        val env: String,
        val app: String,
        val default: Boolean
    ) {
        WEBLEVERANSE_FORMATTED_TXT("utv", "webleveranse", false),
        WEBLEVERANSE_FORMATTED_WITH_DEFAULTS_TXT("utv", "webleveranse", true),
        REFERENCE_FORMATTED_WITH_DEFAULTS_TXT("utv", "reference", true),
        REFERENCE_FORMATTED_TXT("utv", "reference", false)
    }

    @ParameterizedTest
    @EnumSource(SpecStringData::class)
    fun `Should render formatted output for pointers`(test: SpecStringData) {
        val aid = ApplicationDeploymentRef.adr(test.env, test.app)
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid).spec
        val renderedJson = renderJsonForAuroraDeploymentSpecPointers(
            deploymentSpec,
            test.default
        )
        val filename = getFilename(aid, test.default, true, "txt")
        val expected = loadResource(filename)
        assertThat(renderedJson).isEqualTo(expected)
    }

    fun getFilename(
        aid: ApplicationDeploymentRef,
        includevalaults: Boolean,
        formatted: Boolean = false,
        type: String = "json"
    ): String {
        val valaultSuffix = if (includevalaults) "-withDefaults" else ""
        val formattedText = if (formatted) "-formatted" else ""
        return "${aid.application}$formattedText$valaultSuffix.$type"
    }

     */
}