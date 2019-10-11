package no.skatteetaten.aurora.boober.utils

import com.fasterxml.jackson.databind.JsonNode
import io.mockk.clearAllMocks
import io.mockk.mockk
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import org.junit.jupiter.api.BeforeEach

open class AbstractOpenShiftObjectGeneratorTest : AbstractAuroraConfigTest() {

    val userDetailsProvider = mockk<UserDetailsProvider>()

    val mapper = jsonMapper()

    @BeforeEach
    fun setup() {
        clearAllMocks()
    }

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
}
