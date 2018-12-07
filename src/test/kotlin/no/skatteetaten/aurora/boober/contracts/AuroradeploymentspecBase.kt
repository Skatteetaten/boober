package no.skatteetaten.aurora.boober.contracts

import io.mockk.mockk
import org.junit.Before

class AuroradeploymentspecBase : AbstractContractBase() {

    @Before
    fun setUp() {
        loadJsonResponses(this)

        mockk<>
    }
}