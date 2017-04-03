package no.skatteetaten.aurora.boober.controller.security

class User(
        username: String,
        val token: String,
        val fullName: String? = null
) : org.springframework.security.core.userdetails.User(username, token, true, true, true, true, listOf())