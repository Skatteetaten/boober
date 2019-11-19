package no.skatteetaten.aurora.boober.service.openshift.token

import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import org.springframework.stereotype.Component

@Component
class UserDetailsTokenProvider(val userDetailsProvider: UserDetailsProvider) : TokenProvider {

    override fun getToken(): String = userDetailsProvider.getAuthenticatedUser().token
}
