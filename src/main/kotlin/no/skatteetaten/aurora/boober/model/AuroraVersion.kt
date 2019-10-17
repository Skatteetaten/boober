package no.skatteetaten.aurora.boober.model

object AuroraVersion {

    fun isFullAuroraVersion(tag: String): Boolean {
        return Regex("^.*-b(.*)-([a-z]*)-(.*)$").matches(tag)
    }
}
