package no.skatteetaten.aurora.boober.controller.security

import io.fabric8.kubernetes.api.model.authentication.UserInfo
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.service.AzureAdServiceException
import no.skatteetaten.aurora.boober.service.AzureService
import no.skatteetaten.aurora.boober.service.OpenShiftException
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.CredentialsExpiredException
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.stereotype.Component
import java.util.regex.Pattern

private val logger = KotlinLogging.logger {}

@Component
class BearerAuthenticationManager(
    val openShiftClient: OpenShiftClient,
    val azureSerivce: AzureService
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
        val user = getOpenShiftUser(token)

        val username =
            user.username.substringBeforeLast("@") // This username is only the ident on ocp, if we need real name we have to lookup in a cache
        logger.debug("user={} groups={}", username, user.groups)
        val grantedAuthorities = user.groups.filter { it.isNotBlank() }.map {
            if (it.startsWith("system:")) {
                SimpleGrantedAuthority(it)
            } else {
                try {
                    SimpleGrantedAuthority(azureSerivce.resolveGroupName(it))
                } catch (e: AzureAdServiceException) {
                    throw AuthenticationServiceException(e.localizedMessage, e)
                }
            }
        }

        // We need to set isAuthenticated to false to ensure that the http authenticationProvider is also called
        // (don't end the authentication chain).
        return PreAuthenticatedAuthenticationToken(username, token, grantedAuthorities)
            .apply { isAuthenticated = false }
    }

    private fun getOpenShiftUser(token: String): UserInfo {
        return try {
            openShiftClient.findCurrentUser(token)
        } catch (e: OpenShiftException) {
            throw CredentialsExpiredException("An unexpected error occurred while getting OpenShift user", e)
        } ?: throw BadCredentialsException("No user information found for the current token")
    }
}
