package no.skatteetaten.aurora.boober.controller.security;

import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.stereotype.Component
import java.util.regex.Pattern

@Component
class BearerAuthenticationManager : AuthenticationManager {

    private val headerPattern: Pattern = Pattern.compile("Bearer\\s+(.*)", Pattern.CASE_INSENSITIVE)

    override fun authenticate(authentication: Authentication?): Authentication {
        val authenticationHeaderValue = authentication?.principal?.toString()
        val matcher = headerPattern.matcher(authenticationHeaderValue)
        if (!matcher.find()) {
            throw BadCredentialsException("Unexpected Authorization header format")
        }
        val token = matcher.group(1)

        return PreAuthenticatedAuthenticationToken("aurora", token)
    }
}
