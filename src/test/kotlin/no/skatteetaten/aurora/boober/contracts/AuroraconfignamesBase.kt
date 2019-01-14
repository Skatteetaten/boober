package no.skatteetaten.aurora.boober.contracts

import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.Responder
import no.skatteetaten.aurora.boober.controller.v1.AuroraConfigNamesControllerV1
import no.skatteetaten.aurora.boober.service.AuroraConfigService
import org.junit.jupiter.api.BeforeEach

open class AuroraconfignamesBase {

    @BeforeEach
    fun setUp() {
        withContractResponses(this) {
            val auroraConfigService = mockk<AuroraConfigService>(relaxed = true)
            val responder = mockk<Responder>().apply {
                every { create(any()) } returns it.response()
            }
            AuroraConfigNamesControllerV1(auroraConfigService, responder)
        }
    }
}