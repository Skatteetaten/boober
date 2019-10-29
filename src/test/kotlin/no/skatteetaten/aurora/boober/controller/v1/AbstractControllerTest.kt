package no.skatteetaten.aurora.boober.controller.v1

import com.ninjasquad.springmockk.MockkBean
import io.mockk.clearAllMocks
import no.skatteetaten.aurora.boober.controller.security.BearerAuthenticationManager
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc

@WithMockUser
@AutoConfigureRestDocs
abstract class AbstractControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean(relaxed = true)
    lateinit var bearerAuthenticationManager: BearerAuthenticationManager

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
}