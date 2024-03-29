package no.skatteetaten.aurora.boober.unit

import assertk.all
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class OpenshiftApiUrlsTest {

    enum class OpenShiftUrlTestData(
        val kind: String,
        val resourceName: String?,
        val namespace: String?,
        val url: String
    ) {
        USER("user", "foo", null, "/apis/user.openshift.io/v1/users/foo"),
        TEMPLATE("processedtemplate", null, "bar", "/oapi/v1/namespaces/bar/processedtemplates"),
        PROJECT("project", "foo", null, "/apis/project.openshift.io/v1/projects/foo"),
        SERVICE("service", "foo", "bar", "/api/v1/namespaces/bar/services/foo"),
        BIGIP("bigip", "foo", "bar", "/apis/skatteetaten.no/v1/namespaces/bar/bigips/foo"),
        DEPLOYMENT("deployment", "foo", "bar", "/apis/apps/v1/namespaces/bar/deployments/foo"),
        JOB("job", "foo", "bar", "/apis/batch/v1/namespaces/bar/jobs/foo"),
        CRONJOB("cronjob", "foo", "bar", "/apis/batch/v1beta1/namespaces/bar/cronjobs/foo"),
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
            this.isInstanceOf(IllegalArgumentException::class)
            hasMessage("namespace required for resource kind applicationdeployment")
        }
    }
}
