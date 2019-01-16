package no.skatteetaten.aurora.boober.contracts

import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.v1.StsControllerV1
import no.skatteetaten.aurora.boober.controller.v1.StsResponder
import no.skatteetaten.aurora.boober.service.StsRenewService
import org.junit.jupiter.api.BeforeEach

open class StsBase {

    @BeforeEach
    fun setUp() {
        withContractResponses(this) {
            val stsRenewService = mockk<StsRenewService>(relaxed = true)
            val responder = mockk<StsResponder> {
                every { create(any(), any()) } returns it.response()
            }

            StsControllerV1(stsRenewService, responder)
        }
    }
}
