package no.skatteetaten.aurora.boober.contracts

import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.v1.DeployControllerV1
import no.skatteetaten.aurora.boober.controller.v1.DeployResponder
import no.skatteetaten.aurora.boober.service.DeployService
import org.junit.jupiter.api.BeforeEach

open class DeployBase {

    @BeforeEach
    fun setUp() {
        withContractResponses(this) {
            val deployService = mockk<DeployService>(relaxed = true)
            val responder = mockk<DeployResponder>().apply {
                every { create(any()) } returns it.response()
            }
            DeployControllerV1(deployService, responder)
        }
    }
}