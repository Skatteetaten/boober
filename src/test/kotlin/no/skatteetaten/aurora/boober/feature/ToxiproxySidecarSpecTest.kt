package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo

class ToxiproxySidecarSpecTest {

    @Test
    fun findVarNameInFieldNameTest() {

        mapOf(
            "toxiproxy/endpointsFromConfig/test" to "test",
            "toxiproxy/endpointsFromConfig/test/enabled" to "test",
            "toxiproxy/endpointsFromConfig/test/proxyname" to "test",
            "toxiproxy/endpointsFromConfig/test/initialEnabledState" to "test",
            "toxiproxy/endpointsFromConfig/test2" to "test2",
            "toxiproxy/endpointsFromConfig/test2/enabled" to "test2",
            "toxiproxy/endpointsFromConfig/test2/proxyname" to "test2",
            "toxiproxy/endpointsFromConfig/test2/initialEnabledState" to "test2"
        ).forEach { (fieldName, expectedResult) ->
            assertThat(findVarNameInFieldName(ToxiproxyUrlSource.CONFIG_VAR, fieldName)).isEqualTo(expectedResult)
        }

        mapOf(
            "toxiproxy/database/test" to "test",
            "toxiproxy/database/test/enabled" to "test",
            "toxiproxy/database/test/proxyname" to "test",
            "toxiproxy/database/test2" to "test2",
            "toxiproxy/database/test2/enabled" to "test2",
            "toxiproxy/database/test2/proxyname" to "test2"
        ).forEach { (fieldName, expectedResult) ->
            assertThat(findVarNameInFieldName(ToxiproxyUrlSource.DB_SECRET, fieldName)).isEqualTo(expectedResult)
        }
    }

    @Test
    fun findProxyNameInServerAndPortFieldNameTest() {
        mapOf(
            "toxiproxy/serverAndPortFromConfig/test/serverVariable" to "test",
            "toxiproxy/serverAndPortFromConfig/test/portVariable" to "test",
            "toxiproxy/serverAndPortFromConfig/test/initialEnabledState" to "test",
            "toxiproxy/serverAndPortFromConfig/test2/serverVariable" to "test2",
            "toxiproxy/serverAndPortFromConfig/test2/portVariable" to "test2",
            "toxiproxy/serverAndPortFromConfig/test2/initialEnabledState" to "test2"
        ).forEach { (fieldName, expectedResult) ->
            assertThat(findProxyNameInServerAndPortFieldName(fieldName)).isEqualTo(expectedResult)
        }
    }

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
