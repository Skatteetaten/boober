package no.skatteetaten.aurora.boober.controller.security

import org.eclipse.jgit.lib.PersonIdent
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Component
class UserDetailsProvider {

    fun getAuthenticatedUser(): User {

        val authentication = SecurityContextHolder.getContext().authentication
        val user: User = authentication.principal as User
        return user
    }
}