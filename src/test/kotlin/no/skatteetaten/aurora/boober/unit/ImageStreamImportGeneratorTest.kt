package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isNotNull
import com.fasterxml.jackson.databind.ObjectMapper
import no.skatteetaten.aurora.boober.service.internal.ImageStreamImportGenerator
import org.junit.jupiter.api.Test

class ImageStreamImportGeneratorTest {

    @Test
    fun `Create ImageStreamImport with imageStreamName and dockerImageUrl return no null values`() {
        val imageStreamImport =
            ImageStreamImportGenerator.create(
                "dockerImageUrl",
                "imageStreamName",
                "imageStreamNamespace"
            )

        val json = ObjectMapper().writeValueAsString(imageStreamImport)

        assertThat(imageStreamImport).isNotNull()
        assertThat(json).doesNotContain("null")
    }
}
