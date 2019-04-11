package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.BearerAuthenticationManager
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftGroups
import no.skatteetaten.aurora.boober.service.openshift.UserGroup
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

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
            listOf(
                UserGroup("aurora", "APP_PaaS_drift"), UserGroup("aurora", "APP_PaaS_utv")
            )
        )

        val authenticationManager =
            BearerAuthenticationManager(openShiftClient)

        val authentication = authenticationManager.authenticate(TestingAuthenticationToken("Bearer $token", ""))

        assertThat(authentication.isAuthenticated).isFalse()
        assertThat(authentication.authorities).isEqualTo(authorityGroups)
    }
}
