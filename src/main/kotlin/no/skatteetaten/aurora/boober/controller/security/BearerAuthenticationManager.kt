package no.skatteetaten.aurora.boober.controller.security;

import no.skatteetaten.aurora.boober.service.internal.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.CredentialsExpiredException
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.stereotype.Component
import java.util.regex.Pattern

@Component
class BearerAuthenticationManager(
        val openShiftClient: OpenShiftClient
) : AuthenticationManager {

    private val headerPattern: Pattern = Pattern.compile("Bearer\\s+(.*)", Pattern.CASE_INSENSITIVE)

    override fun authenticate(authentication: Authentication?): Authentication {
        val authenticationHeaderValue = authentication?.principal?.toString()
        val matcher = headerPattern.matcher(authenticationHeaderValue)
        if (!matcher.find()) {
            throw BadCredentialsException("Unexpected Authorization header format")
        }
        val token = matcher.group(1)

        val response = try {
            openShiftClient.findCurrentUser(token)
        } catch (e: OpenShiftException) {
            throw CredentialsExpiredException("An unexpected error occurred while getting OpenShift user", e)
        }

        return PreAuthenticatedAuthenticationToken(response, token)
    }
}
