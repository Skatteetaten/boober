package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo

class ToxiproxySidecarSpecTest {
    @Test
    fun getNextPortNumberTest() {
        val toxiproxyConfigs = listOf(
            ToxiproxyConfig("proxyname1", "0.0.0.0:18000", "test1.test:80", true),
            ToxiproxyConfig("proxyname3", "0.0.0.0:18003", "test3.test:80", true),
            ToxiproxyConfig("proxyname2", "0.0.0.0:18001", "test2.test:80", true)
        )
        val numberIfEmpty = 18000
        assertThat(toxiproxyConfigs.getNextPortNumber(numberIfEmpty)).isEqualTo(18004)
        assertThat(emptyList<ToxiproxyConfig>().getNextPortNumber(numberIfEmpty)).isEqualTo(numberIfEmpty)
    }
}
