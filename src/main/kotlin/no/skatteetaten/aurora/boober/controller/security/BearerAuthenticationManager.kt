package no.skatteetaten.aurora.boober.controller.security

import com.fasterxml.jackson.databind.JsonNode
import java.util.regex.Pattern
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.CredentialsExpiredException
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.stereotype.Component

@Component
class BearerAuthenticationManager(
    val openShiftClient: OpenShiftClient
) : AuthenticationManager {

    val logger: Logger = LoggerFactory.getLogger(BearerAuthenticationManager::class.java)

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
        val openShiftUser = getOpenShiftUser(token)
        val grantedAuthorities = getGrantedAuthoritiesForUser(openShiftUser)

        // We need to set isAuthenticated to false to ensure that the http authenticationProvider is also called
        // (don't end the authentication chain).
        return PreAuthenticatedAuthenticationToken(openShiftUser, token, grantedAuthorities)
            .apply { isAuthenticated = false }
    }

    private fun getGrantedAuthoritiesForUser(openShiftUser: JsonNode?): List<SimpleGrantedAuthority> {
        val username: String = openShiftUser?.openshiftName
            ?: throw IllegalArgumentException("Unable to determine username from response")

        return openShiftClient.getGroups().getGroupsForUser(username)
            .map { SimpleGrantedAuthority(it) }
    }

    private fun getOpenShiftUser(token: String): JsonNode {
        return try {
            openShiftClient.findCurrentUser(token)
        } catch (e: OpenShiftException) {
            throw CredentialsExpiredException("An unexpected error occurred while getting OpenShift user", e)
        } ?: throw BadCredentialsException("No user information found for the current token")
    }
}
