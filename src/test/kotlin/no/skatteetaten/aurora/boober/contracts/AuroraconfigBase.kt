package no.skatteetaten.aurora.boober.contracts

import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigControllerV1
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigResponder
import no.skatteetaten.aurora.boober.model.AuroraConfig
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import org.junit.jupiter.api.BeforeEach

open class AuroraconfigBase {

    @BeforeEach
    fun setUp() {
        withContractResponses(this) {
            val auroraConfigService = mockk<AuroraConfigService>(relaxed = true)
            val responder = mockk<AuroraConfigResponder>().apply {
                every { create(any<AuroraConfig>()) } returns it.response("auroraconfig")
                every { create(any<AuroraConfigFile>()) } returns it.response("auroraconfigfile")
                every { create(any<List<String>>()) } returns it.response("filenames")
            }

            AuroraConfigControllerV1(auroraConfigService, responder)
        }
    }
}