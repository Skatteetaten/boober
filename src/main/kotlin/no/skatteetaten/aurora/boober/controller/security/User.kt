package no.skatteetaten.aurora.boober.controller.security

import org.springframework.security.core.userdetails.User as SpringSecurityUser

class User(
        username: String,
        val token: String,
        val fullName: String? = null
) : SpringSecurityUser(username, token, true, true, true, true, listOf())