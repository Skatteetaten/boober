package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.text.StringSubstitutor
import org.junit.jupiter.api.Test

class TemplateProcessorTest {

    @Test
    fun `should process template`() {

        val mapper = ObjectMapper()
        val template: String = this.javaClass.getResource("/samples/config/templates/atomhopper.json").readText()

        val templateJson: JsonNode = mapper.readTree(template)

        val parameters = templateJson.at("/parameters")

        val valueParameters =
            parameters.filter { it["value"] != null }.associate { it["name"].asText() to it["value"].asText() }

        val replacer: StringSubstitutor = StringSubstitutor(
            valueParameters + mapOf(
                "NAME" to "tvinn",
                "VERSION" to "1",
                "SPLUNK_INDEX" to "safir",
                "FEED_NAME" to "tolldeklarasjon",
                "DB_NAME" to "tvinn",
                "HOST_NAME" to "localhost",
                "DOMAIN_NAME" to "localhost"
            ), "\${", "}"
        )

        val replacedText = replacer.replace(template)

        val result = mapper.readTree(replacedText)
        val atomhopperTemplate: JsonNode =
            mapper.readTree(this.javaClass.getResource("/samples/processedtemplate/booberdev/tvinn/atomhopper.json"))

        val resultString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)
        val expextedString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(atomhopperTemplate)
        assertThat(resultString).isEqualTo(expextedString)
    }
}