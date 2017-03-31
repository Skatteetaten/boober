package no.skatteetaten.aurora.boober.model


data class Rolebinding(val groupNames: List<String>, val userNames: List<String>) {
    val subjects: List<KindName>
        get() = groupNames.map { KindName("Group", it) }.plus(userNames.map { KindName("User", it) })
}

data class KindName(val kind: String, val name: String)