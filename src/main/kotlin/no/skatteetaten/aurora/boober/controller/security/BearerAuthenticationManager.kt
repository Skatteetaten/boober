package no.skatteetaten.aurora.boober.controller.security

import com.fasterxml.jackson.databind.JsonNode
import java.nio.charset.Charset
import java.util.regex.Pattern
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.ServiceTypes
import no.skatteetaten.aurora.boober.TargetService
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.utils.base64UrlDecode
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForObject
import org.springframework.web.util.UriUtils

private val logger = KotlinLogging.logger {}

@Component
class BearerAuthenticationManager(
    val openShiftClient: OpenShiftClient,
    @TargetService(ServiceTypes.EKS) val restTemplate: RestTemplate
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

        val user = getUser(token)
        val grantedAuthority = getGrantedAuthoritiesForUser(user)

        return PreAuthenticatedAuthenticationToken(user, token, grantedAuthority)
            .apply { isAuthenticated = false }
    }

    private fun getGrantedAuthoritiesForUser(username: String): List<SimpleGrantedAuthority> {
        return openShiftClient.getGroups().getGroupsForUser(username)
            .map { SimpleGrantedAuthority(it) }
    }

    // TODO: error handling
    fun getUser(token: String): String {
        val fixedToken = token.removePrefix("k8s-aws-v1.")

        val request = UriUtils.decode(fixedToken.base64UrlDecode(), Charset.defaultCharset())

        val result: JsonNode = restTemplate.getForObject(request)

        return result.at("/GetCallerIdentityResponse/GetCallerIdentityResult/Arn").textValue()
    }
}
