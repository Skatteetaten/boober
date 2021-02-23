package no.skatteetaten.aurora.boober.unit

import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.BearerAuthenticationManager
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftGroups

class BearerAuthenticationManagerTest {
    val username = "aurora"
    val groups = listOf("APP_PaaS_drift", "APP_PaaS_utv")
    val token = "some_token"
    val authorityGroups = groups.map { SimpleGrantedAuthority(it) }

    @Test
    fun `Gets authorities from OpenShift groups`() {

        val objectMapper = ObjectMapper()
        val openShiftClient = mockk<OpenShiftClient>()

        every {
            openShiftClient.findCurrentUser(token)
        } returns objectMapper.readValue<JsonNode>("""{"kind": "user", "metadata": {"name": "$username"}, "fullName": "Aurora Test User"}""")

        every {
            openShiftClient.getGroups()
        } returns OpenShiftGroups(
            mapOf(
                "APP_PaaS_drift" to listOf("aurora"),
                "APP_PaaS_utv" to listOf("aurora")
            )
        )

        val authenticationManager =
            BearerAuthenticationManager(openShiftClient)

        val authentication = authenticationManager.authenticate(TestingAuthenticationToken("Bearer $token", ""))

        assertThat(authentication.isAuthenticated).isFalse()
        assertThat(authentication.authorities).isEqualTo(authorityGroups)
    }
}
