package no.skatteetaten.aurora.boober.unit

import org.springframework.security.core.authority.SimpleGrantedAuthority

class BearerAuthenticationManagerTest {
    val username = "aurora"
    val groups = listOf("APP_PaaS_drift", "APP_PaaS_utv")
    val token = "some_token"
    val authorityGroups = groups.map { SimpleGrantedAuthority(it) }

    /*
    @Ignore("kubernetes")
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
    }*/
}
