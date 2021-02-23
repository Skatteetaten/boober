package no.skatteetaten.aurora.boober.controller.v1

import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk

@WebMvcTest(
    controllers = [ClientConfigControllerV1::class],
    properties = ["integrations.aurora.config.git.urlPattern=abc", "openshift.cluster=test", "integrations.openshift.url=test"]
)
class ClientConfigControllerV1Test : AbstractControllerTest() {

    @Test
    fun `Return client config`() {
        mockMvc.get(Path("/v1/clientconfig")) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
                .responseJsonPath("$.items[0].gitUrlPattern").equalsValue("abc")
                .responseJsonPath("$.items[0].openshiftCluster").equalsValue("test")
                .responseJsonPath("$.items[0].openshiftUrl").equalsValue("test")
                .responseJsonPath("$.items[0].apiVersion").equalsValue(2)
        }
    }
}
