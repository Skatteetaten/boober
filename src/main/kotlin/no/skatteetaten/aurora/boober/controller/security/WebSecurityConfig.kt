package no.skatteetaten.aurora.boober.controller.security

import org.slf4j.MDC
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter
import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.utils.openshiftName

private val logger = KotlinLogging.logger {}

@EnableWebSecurity
class WebSecurityConfig(
    val authenticationManager: BearerAuthenticationManager
) : WebSecurityConfigurerAdapter() {

    override fun configure(http: HttpSecurity) {

        http.csrf().disable()

        http.authenticationProvider(preAuthenticationProvider())
            .addFilter(requestHeaderAuthenticationFilter())
            .authorizeRequests()
            // EndpointRequest.toAnyEndpoint() points to all actuator endpoints and then permitAll requests
            .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
            .antMatchers("/v1/clientconfig").permitAll()
            .antMatchers("/v1/auroraconfignames").permitAll()
            .anyRequest().authenticated()
    }

    @Bean
    internal fun preAuthenticationProvider() = PreAuthenticatedAuthenticationProvider().apply {
        setPreAuthenticatedUserDetailsService {

            val principal: JsonNode? = it.principal as JsonNode?
            val username: String = principal?.openshiftName
                ?: throw IllegalArgumentException("Unable to determine username from response")
            val fullName: String? = principal.get("fullName")?.asText()

            MDC.put("user", username)
            User(username, it.credentials as String, fullName, it.authorities).also {
                logger.info("Logged in user username=$username, name='$fullName' tokenSnippet=${it.tokenSnippet} groups=${it.groupNames}")
            }
        }
    }

    @Bean
    internal fun requestHeaderAuthenticationFilter() = RequestHeaderAuthenticationFilter().apply {
        setPrincipalRequestHeader("Authorization")
        setExceptionIfHeaderMissing(false)
        setAuthenticationManager(authenticationManager)
    }
}
