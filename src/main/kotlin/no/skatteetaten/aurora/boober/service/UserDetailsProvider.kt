package no.skatteetaten.aurora.boober.service

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import no.skatteetaten.aurora.boober.controller.security.User

@Component
class UserDetailsProvider {

    fun getAuthenticatedUser(): User {

        val authentication = SecurityContextHolder.getContext().authentication
        val user: User = authentication.principal as User
        return user
    }
}
