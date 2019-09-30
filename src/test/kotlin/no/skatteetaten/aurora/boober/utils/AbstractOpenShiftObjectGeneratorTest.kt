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
