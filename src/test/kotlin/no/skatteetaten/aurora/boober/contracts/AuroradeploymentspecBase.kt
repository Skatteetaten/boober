package no.skatteetaten.aurora.boober.contracts

import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.v1.AuroraDeploymentSpecControllerV1
import no.skatteetaten.aurora.boober.controller.v1.AuroraDeploymentSpecResponder
import no.skatteetaten.aurora.boober.mapper.AuroraDeploymentSpec
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
import org.junit.jupiter.api.BeforeEach

open class AuroradeploymentspecBase {

    @BeforeEach
    fun setUp() {
        contractResponses<AuroradeploymentspecBase> {
            val auroraDeploymentSpecService = mockk<AuroraDeploymentSpecService>().apply {
                every { getAuroraDeploymentSpecs(any(), any()) } returns listOf(AuroraDeploymentSpec(emptyMap()))
            }

            val auroraDeploymentSpecResponder = mockk<AuroraDeploymentSpecResponder>().apply {
                every { create(any()) } returns it.response("deploymentspec-formatted")
                every {
                    create(any<List<AuroraDeploymentSpec>>(), any())
                } returns it.response("deploymentspec")
            }

            AuroraDeploymentSpecControllerV1(auroraDeploymentSpecService, auroraDeploymentSpecResponder)
        }
    }
}