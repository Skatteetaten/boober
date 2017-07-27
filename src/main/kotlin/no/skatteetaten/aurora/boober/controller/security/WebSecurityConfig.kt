package no.skatteetaten.aurora.boober.controller.security

import com.fasterxml.jackson.databind.JsonNode
import no.skatteetaten.aurora.boober.utils.openshiftName
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter
import org.springframework.security.web.util.matcher.RequestMatcher
import javax.servlet.http.HttpServletRequest

@EnableWebSecurity
class WebSecurityConfig(
        val authenticationManager: BearerAuthenticationManager,
        @Value("\${management.port}") val managementPort: Int
) : WebSecurityConfigurerAdapter() {


    override fun configure(http: HttpSecurity) {

        http.csrf().disable()

        http.authenticationProvider(preAuthenticationProvider())
                .addFilter(requestHeaderAuthenticationFilter())
                .authorizeRequests()
                .requestMatchers(forPort(managementPort)).permitAll()
                .anyRequest().authenticated()
    }

    private fun forPort(port: Int) = RequestMatcher { request: HttpServletRequest -> port == request.localPort }

    @Bean
    internal fun preAuthenticationProvider() = PreAuthenticatedAuthenticationProvider().apply {
        setPreAuthenticatedUserDetailsService({ it: PreAuthenticatedAuthenticationToken ->
            val principal: JsonNode? = it.principal as JsonNode?
            val username: String = principal?.openshiftName ?: throw IllegalArgumentException("Unable to determine username from response")
            val fullName: String? = principal.get("fullName")?.asText()

            User(username, it.credentials as String, fullName)
        })
    }

    @Bean
    internal fun requestHeaderAuthenticationFilter() = RequestHeaderAuthenticationFilter().apply {
        setPrincipalRequestHeader("Authorization")
        setExceptionIfHeaderMissing(false)
        setAuthenticationManager(authenticationManager)
    }
}