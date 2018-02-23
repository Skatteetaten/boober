package no.skatteetaten.aurora.boober.service

import no.skatteetaten.aurora.boober.controller.security.User
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Component
class UserDetailsProvider {

    fun getAuthenticatedUser(): User {

        val authentication = SecurityContextHolder.getContext()
            .authentication
        val user: User = authentication.principal as User
        return user
    }
}