package no.skatteetaten.aurora.boober.service.openshift

interface TokenProvider {
    fun getToken(): String
}