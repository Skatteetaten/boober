package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import no.skatteetaten.aurora.boober.controller.security.User
import org.junit.jupiter.api.Test

class UserTest {

    @Test
    fun `service account with username equals rolename should have access`() {
        val user = User("system:serviceaccount:foobar-build-test:jenkins-builder", "asfd111111123", "Jenkins ")
        assertThat(
            user.hasAccess(
                listOf(
                    "APP_foobar_utv",
                    "system:serviceaccount:foobar-build-test:jenkins-builder"
                )
            )
        ).isTrue()
    }

    @Test
    fun `normal user with  username equals rolename should not have access`() {
        val user = User("linus", "asfd111111123", "Jenkins ")
        assertThat(user.hasAccess(listOf("APP_foobar_utv", "linus"))).isFalse()
    }

    @Test
    fun `return a token snippet containing the last five characters`() {
        val user = User("linus", "abcde12345", "Jenkins ")
        assertThat(user.tokenSnippet).isEqualTo("12345")
    }
}
