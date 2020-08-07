package no.skatteetaten.aurora.boober.controller.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.User as SpringSecurityUser

class User(
    username: String,
    val token: String,
    val fullName: String? = null,
    grantedAuthorities: Collection<GrantedAuthority> = listOf()
) : SpringSecurityUser(username, token, true, true, true, true, grantedAuthorities.toList()) {

    fun hasRole(role: String): Boolean {

        return authorities.any { it.authority == role }
    }

    fun hasAccess(roles: Collection<String>): Boolean {
        return (this.username.startsWith("system:serviceaccount") && roles.contains(this.username)) ||
            this.hasAnyRole(roles)
    }

    fun hasAnyRole(roles: Collection<String>?): Boolean {

        if (roles?.isEmpty() != false) return true

        // TODO: Remove replace
        return authorities.any { roles.contains(it.authority) }
    }

    val tokenSnippet: String
        get() = token.takeLast(5)

    val groupNames: List<String>
        get() = authorities.map { it.authority }
}
