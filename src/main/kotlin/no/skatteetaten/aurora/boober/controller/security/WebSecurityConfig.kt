package no.skatteetaten.aurora.boober.controller.security

import no.skatteetaten.aurora.boober.controller.security.BearerAuthenticationManager
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.core.userdetails.User
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.security.web.authentication.preauth.RequestHeaderAuthenticationFilter

@EnableWebSecurity
class WebSecurityConfig(
        val authenticationManager: BearerAuthenticationManager
) : WebSecurityConfigurerAdapter() {

    override fun configure(http: HttpSecurity) {

        http.csrf().disable()

        http.authenticationProvider(preAuthenticationProvider())
                .addFilter(requestHeaderAuthenticationFilter())
                .authorizeRequests().anyRequest().authenticated()
    }

    @Bean
    internal fun preAuthenticationProvider() = PreAuthenticatedAuthenticationProvider().apply {
        setPreAuthenticatedUserDetailsService({ it: PreAuthenticatedAuthenticationToken ->
            User(it.principal as String, it.credentials as String, it.authorities)
        })
    }

    @Bean
    internal fun requestHeaderAuthenticationFilter() = RequestHeaderAuthenticationFilter().apply {
        setPrincipalRequestHeader("Authorization")
        setExceptionIfHeaderMissing(false)
        setAuthenticationManager(authenticationManager)
    }
}