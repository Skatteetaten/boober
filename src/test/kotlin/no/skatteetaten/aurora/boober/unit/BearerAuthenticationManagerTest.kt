package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.BearerAuthenticationManager
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

class BearerAuthenticationManagerTest {
    val username = "aurora"
    val groups = listOf("group1", "group2")
    val token = "some_token"
    val authorityGroups = groups.map { SimpleGrantedAuthority(it) }

    @Test
    fun `Gets authorities from OpenShift groups`() {

        val objectMapper = ObjectMapper()
        val openShiftClient = mockk<OpenShiftClient>()

        every {
            openShiftClient.findCurrentUser(token)
        } returns objectMapper.readValue("""
            {
            "groups": [
                "group1",
                "group2",
                ""
            ],
            "username": "$username"
            }""".trimIndent())

        val authenticationManager =
            BearerAuthenticationManager(openShiftClient)

        val authentication = authenticationManager.authenticate(TestingAuthenticationToken("Bearer $token", ""))

        assertThat(authentication.isAuthenticated).isFalse()
        assertThat(authentication.authorities).isEqualTo(authorityGroups)
    }
}
