package no.skatteetaten.aurora.boober.utils

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.JsonNode
import io.mockk.clearAllMocks
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import org.junit.jupiter.api.BeforeEach

open class AbstractOpenShiftObjectGeneratorTest : AbstractAuroraConfigTest() {

    val DEPLOY_ID = "123"

    val userDetailsProvider = mockk<UserDetailsProvider>()

    val openShiftResourceClient = mockk<OpenShiftResourceClient>()
    val mapper = jsonMapper()

    @BeforeEach
    fun setup() {
        clearAllMocks()
    }


    /*
    fun specJavaWithToxiproxy(): AuroraDeploymentSpecInternal {
        return createDeploymentSpec(
            mapOf(
                "about.json" to DEFAULT_ABOUT,
                "utv/about.json" to DEFAULT_UTV_ABOUT,
                "reference.json" to REF_APP_JSON,
                "utv/reference.json" to """{ "toxiproxy" : { "version" : "2.1.3" } }"""
            ), adr("utv", "reference")
        )
    }

    fun specWebWithToxiproxy(): AuroraDeploymentSpecInternal {
        return createDeploymentSpec(
            mapOf(
                "about.json" to DEFAULT_ABOUT,
                "utv/about.json" to DEFAULT_UTV_ABOUT,
                "webleveranse.json" to WEB_LEVERANSE,
                "utv/webleveranse.json" to """{ "type": "deploy", "version" : "1.0.8", "database" : { "REFerence" : "auto" }, "toxiproxy" : { "version" : "2.1.3" } }"""
            ), adr("utv", "webleveranse")
        )
    }
    */
    fun getKey(it: JsonNode): String {
        val kind = it.get("kind").asText().toLowerCase()
        val metadata = it.get("metadata")

        val name = if (metadata == null) {
            it.get("name").asText().toLowerCase()
        } else {
            metadata.get("name").asText().toLowerCase()
        }

        return "$kind/$name"
    }


    // TODO: almost duplicate elsewhere
    fun compareJson(file: String, jsonNode: JsonNode, target: JsonNode): Boolean {
        val writer = mapper.writerWithDefaultPrettyPrinter()
        val targetString = writer.writeValueAsString(target)
        val nodeString = writer.writeValueAsString(jsonNode)
        val expected = "$file\n" + targetString
        val actual = "$file\n" + nodeString
        assertThat(expected).isEqualTo(actual)
        return true
    }
}
