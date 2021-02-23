package no.skatteetaten.aurora.boober.service.openshift.token

import org.springframework.stereotype.Component
import no.skatteetaten.aurora.boober.service.UserDetailsProvider

@Component
class UserDetailsTokenProvider(val userDetailsProvider: UserDetailsProvider) : TokenProvider {

    override fun getToken(): String = userDetailsProvider.getAuthenticatedUser().token
}
