package no.skatteetaten.aurora.boober.contracts

import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.Responder
import no.skatteetaten.aurora.boober.controller.v1.ApplyResultController
import no.skatteetaten.aurora.boober.service.DeployHistoryEntry
import no.skatteetaten.aurora.boober.service.DeployLogService
import org.junit.jupiter.api.BeforeEach

open class ApplyresultBase {

    @BeforeEach
    fun setUp() {
        withContractResponses(this) {
            val deployLogService = mockk<DeployLogService>(relaxed = true)
            val responder = mockk<Responder>().apply {
                every { create(any<List<DeployHistoryEntry>>()) } returns it.response()
                every { create(any<DeployHistoryEntry>()) } returns it.response()
            }

            ApplyResultController(deployLogService, responder)
        }
    }
}