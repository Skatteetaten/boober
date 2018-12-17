package no.skatteetaten.aurora.boober.contracts

import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.Responder
import no.skatteetaten.aurora.boober.controller.v1.ClientConfigControllerV1
import org.junit.jupiter.api.BeforeEach

open class ClientconfigBase {

    @BeforeEach
    fun setUp() {
        withContractResponses(this) {
            val responder = mockk<Responder>().apply {
                every { create(any()) } returns it.response()
            }
            ClientConfigControllerV1("", "", "", responder)
        }
    }
}