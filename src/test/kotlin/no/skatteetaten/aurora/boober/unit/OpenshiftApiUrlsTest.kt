package no.skatteetaten.aurora.boober.unit

import assertk.all
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import org.junit.Ignore
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@Ignore("kubernetes")
class OpenshiftApiUrlsTest {

    enum class OpenShiftUrlTestData(
        val kind: String,
        val resourceName: String?,
        val namespace: String?,
        val url: String
    ) {
        SERVICE("service", "foo", "bar", "/api/v1/namespaces/bar/services/foo")
    }

    @ParameterizedTest
    @EnumSource(OpenShiftUrlTestData::class)
    fun `Should create correct url`(test: OpenShiftUrlTestData) {

        val result = OpenShiftResourceClient.generateUrl(test.kind, test.namespace, test.resourceName)
        assertThat(result).isEqualTo(test.url)
    }

    @Test
    fun `Should get exception if generating url that requires namespace with no namespace`() {

        assertThat {
            OpenShiftResourceClient.generateUrl("applicationdeployment", null, null)
        }.isFailure().all {
            isInstanceOf(IllegalArgumentException::class)
            hasMessage("namespace required for resource kind applicationdeployment")
        }
    }
}
