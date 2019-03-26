package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.model.AbstractAuroraConfigTest2
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.utils.jsonMapper
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class AuroraDeploymentSpecRendererTest : AbstractAuroraConfigTest2() {

    val auroraConfigJson = mutableMapOf(
        "about.json" to DEFAULT_ABOUT,
        "utv/about.json" to DEFAULT_UTV_ABOUT,
        "webleveranse.json" to WEB_LEVERANSE,
        "reference.json" to REFERENCE_WEBSEAL,
        "utv/reference.json" to """{ "webseal" : { "roles" : "foobar" }}""",
        "utv/webleveranse.json" to """{ "type": "development", "version": "1" }"""
    )
    val mapper = jsonMapper()

    enum class PointerStringTestData(
        val env: String,
        val app: String,
        val default: Boolean
    ) {
        WEB("utv", "webleveranse", false),
        WEB_ALL("utv", "webleveranse", true),
        REF_ALL("utv", "reference", true),
        REF("utv", "reference", false)
    }

    @ParameterizedTest
    @EnumSource(PointerStringTestData::class)
    fun `Should create a Map of AuroraDeploymentSpec pointers`(test: PointerStringTestData) {
        val aid = ApplicationDeploymentRef.aid(test.env, test.app)
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid).spec

        val renderedJson = renderSpecAsJson(deploymentSpec, test.default)
        val filename = getFilename(aid, test.default)
        val expected = loadResource(filename)

        val json = mapper.readTree(mapper.writeValueAsString(renderedJson))
        val expectedJson = mapper.readTree(expected)
        compareJson(expectedJson, json)
    }

    enum class PointerJsonTestData(
        val env: String,
        val app: String,
        val default: Boolean
    ) {
        WEB("utv", "webleveranse", false),
        WEB_ALL("utv", "webleveranse", true),
        REF_ALL("utv", "reference", true),
        REF("utv", "reference", false)
    }

    @ParameterizedTest
    @EnumSource(PointerJsonTestData::class)
    fun `Should render formatted json-like output for pointers`(test: PointerJsonTestData) {
        val aid = ApplicationDeploymentRef.aid(test.env, test.app)
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, aid).spec
        val renderedJson = renderJsonForAuroraDeploymentSpecPointers(deploymentSpec, test.default)
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

        return "${aid.application}${formattedText}${valaultSuffix}.${type}"
    }

    fun compareJson(jsonNode: JsonNode, target: JsonNode): Boolean {
        val writer = mapper.writerWithDefaultPrettyPrinter()
        val targetString = writer.writeValueAsString(target)
        val nodeString = writer.writeValueAsString(jsonNode)
        assertThat(targetString).isEqualTo(nodeString)
        return true
    }
}
