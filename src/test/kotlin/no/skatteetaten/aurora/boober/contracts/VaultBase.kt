package no.skatteetaten.aurora.boober.contracts

import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.Responder
import no.skatteetaten.aurora.boober.controller.v1.VaultControllerV1
import no.skatteetaten.aurora.boober.controller.v1.VaultWithAccessResource
import no.skatteetaten.aurora.boober.service.vault.VaultService
import org.junit.jupiter.api.BeforeEach

open class VaultBase {

    @BeforeEach
    fun setUp() {
        withContractResponses(this) {
            val vaultService = mockk<VaultService>(relaxed = true)
            val responder = mockk<Responder>().apply {
                every { create() } returns it.response("empty-vault")
                every { create(any<List<VaultWithAccessResource>>()) } returns it.response("vaults")
            }
            VaultControllerV1(vaultService, responder, true)
        }
    }
}