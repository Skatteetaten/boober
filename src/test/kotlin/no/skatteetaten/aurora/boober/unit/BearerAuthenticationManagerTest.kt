package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.BearerAuthenticationManager
import no.skatteetaten.aurora.boober.service.openshift.KubernetesGroups
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.client.RestTemplate

class BearerAuthenticationManagerTest {
    val username = "aurora"
    val groups = listOf("APP_PaaS_drift", "APP_PaaS_utv")
    val token = "some_token"
    val authorityGroups = groups.map { SimpleGrantedAuthority(it) }

    @Disabled
    @Test
    fun `Gets authorities from OpenShift groups`() {

        val objectMapper = ObjectMapper()
        val openShiftClient = mockk<OpenShiftClient>()
        val restTemplate = mockk<RestTemplate>()

        every {
            openShiftClient.getGroups()
        } returns KubernetesGroups(
            mapOf(
                "APP_PaaS_drift" to listOf("aurora"),
                "APP_PaaS_utv" to listOf("aurora")
            )
        )

        val authenticationManager =
            BearerAuthenticationManager(openShiftClient, restTemplate)

        val authentication = authenticationManager.authenticate(TestingAuthenticationToken("Bearer $token", ""))

        assertThat(authentication.isAuthenticated).isFalse()
        assertThat(authentication.authorities).isEqualTo(authorityGroups)
    }
}
