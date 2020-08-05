package no.skatteetaten.aurora.boober.controller.security

import com.fasterxml.jackson.databind.JsonNode
import java.util.regex.Pattern
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.CredentialsExpiredException
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class BearerAuthenticationManager(
    val openShiftClient: OpenShiftClient
) : AuthenticationManager {

    companion object {
        private val headerPattern: Pattern = Pattern.compile("Bearer\\s+(.*)", Pattern.CASE_INSENSITIVE)

        private fun getBearerTokenFromAuthentication(authentication: Authentication?): String {
            val authenticationHeaderValue = authentication?.principal?.toString()
            val matcher = headerPattern.matcher(authenticationHeaderValue)
            if (!matcher.find()) {
                throw BadCredentialsException("Unexpected Authorization header format")
            }
            return matcher.group(1)
        }
    }

    override fun authenticate(authentication: Authentication?): Authentication {

        val token = getBearerTokenFromAuthentication(authentication)
        val grantedAuthorities = listOf(SimpleGrantedAuthority("APP_PaaS_utv"), SimpleGrantedAuthority("8007f31c-c04f-4184-946c-220bae98c592"))

        // We need to set isAuthenticated to false to ensure that the http authenticationProvider is also called
        // (don't end the authentication chain).
        return PreAuthenticatedAuthenticationToken("espen", token, grantedAuthorities)
            .apply { isAuthenticated = false }
    }

    private fun getGrantedAuthoritiesForUser(openShiftUser: JsonNode?): List<SimpleGrantedAuthority> {
        val username: String = openShiftUser?.openshiftName
            ?: throw IllegalArgumentException("Unable to determine username from response")

        return openShiftClient.getGroups().getGroupsForUser(username)
            .map { SimpleGrantedAuthority(it) }
    }

    // TODO: Implement TokenReview as we do in mokey.
    private fun getOpenShiftUser(token: String): JsonNode {
        return try {
            openShiftClient.findCurrentUser(token)
        } catch (e: OpenShiftException) {
            throw CredentialsExpiredException("An unexpected error occurred while getting OpenShift user", e)
        } ?: throw BadCredentialsException("No user information found for the current token")
    }
}
