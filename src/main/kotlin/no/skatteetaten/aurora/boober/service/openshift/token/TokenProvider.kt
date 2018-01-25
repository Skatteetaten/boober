package no.skatteetaten.aurora.boober.service.openshift.token

interface TokenProvider {
    fun getToken(): String
}