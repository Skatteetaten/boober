package no.skatteetaten.aurora.boober.controller.security

import javax.servlet.http.HttpServletRequest
import mu.KotlinLogging
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter
import org.springframework.security.web.util.matcher.RequestMatcher

private val logger = KotlinLogging.logger {}

@EnableWebSecurity
class WebSecurityConfig(
    val authenticationManager: BearerAuthenticationManager,
    @Value("\${management.server.port}") val managementPort: Int
) : WebSecurityConfigurerAdapter() {

    override fun configure(http: HttpSecurity) {

        http.csrf().disable()

        http.authenticationProvider(preAuthenticationProvider())
            .addFilter(requestHeaderAuthenticationFilter())
            .authorizeRequests()
            .requestMatchers(forPort(managementPort)).permitAll()
            .antMatchers("/v1/clientconfig").permitAll()
            .antMatchers("/v1/auroraconfignames").permitAll()
            .antMatchers("/v2/group/**").permitAll()
            .anyRequest().authenticated()
    }

    private fun forPort(port: Int) = RequestMatcher { request: HttpServletRequest -> port == request.localPort }

    @Bean
    internal fun preAuthenticationProvider() = PreAuthenticatedAuthenticationProvider().apply {
        setPreAuthenticatedUserDetailsService {

            val username = it.principal as String
            val fullName = username.substringAfterLast("@").replace(".", " ")

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
