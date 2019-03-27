package no.skatteetaten.aurora.boober.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.mapper.v1.AuroraVolumeMapperV1
import no.skatteetaten.aurora.boober.model.AbstractAuroraConfigTest
import org.junit.jupiter.api.Test

class AuroraConfigFieldTest : AbstractAuroraConfigTest() {

    @Test
    fun `Should generate correct config extractors`() {
        val auroraConfigJson = defaultAuroraConfig()
        auroraConfigJson["utv/aos-simple.json"] = """{
        "type": "deploy",
        "config": {
        "foo": "baaaar",
        "bar": "bar",
        "1": {
        "bar": "baz",
        "foo": "baz"
    }
    }
    }"""

        val auroraConfig = createAuroraConfig(auroraConfigJson)
        val files = auroraConfig.files
        val mapper = AuroraVolumeMapperV1(files)

        assertThat(mapper.configHandlers.map { it.path }).isEqualTo(
            listOf(
                "/config/foo",
                "/config/bar",
                "/config/1/bar",
                "/config/1/foo"
            )
        )
    }
}