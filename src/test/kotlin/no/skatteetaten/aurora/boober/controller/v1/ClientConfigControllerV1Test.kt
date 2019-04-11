package no.skatteetaten.aurora.boober.controller.v1

import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc

@AutoConfigureRestDocs
@WebMvcTest(
    controllers = [ClientConfigControllerV1::class],
    properties = ["boober.git.urlPattern=abc", "openshift.cluster=test", "openshift.url=test"],
    secure = false
)
class ClientConfigControllerV1Test(@Autowired private val mockMvc: MockMvc) {

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