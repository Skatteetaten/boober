package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.controller.v1.getRefNameFromRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

class RequestHelperTest {

    lateinit var request: MockHttpServletRequest

    @BeforeEach
    fun setup() {
        request = MockHttpServletRequest()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    @Test
    fun `Get ref-name from request given ref input`() {
        request.addParameter("reference", "abc123")
        val refName = getRefNameFromRequest()
        assertThat(refName).isEqualTo("abc123")
    }

    @Test
    fun `Get ref-name from request given request header`() {
        request.addHeader("Ref-Name", "header")
        val refName = getRefNameFromRequest()
        assertThat(refName).isEqualTo("header")
    }

    @Test
    fun `Get ref-name given no ref input`() {
        val refName = getRefNameFromRequest()
        assertThat(refName).isEqualTo("master")
    }
}
