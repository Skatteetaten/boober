package no.skatteetaten.aurora.boober.utils

import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.UrlValidationException
import java.net.URI

class Url(val url: String) {

    private val uri: URI? = when {
        isOracleJdbcUrl() -> null
        isPostgresJdbcUrl() -> URI(
            if (url.startsWith("jdbc:postgresql://")) {
                url
            } else {
                // If hostname is not given, it should default to localhost
                url.replaceFirst("sql:", "sql://localhost/")
            }.removePrefix("jdbc:")
        )
        else -> URI(url)
    }

    val hostName: String get() {
        assertIsValid()
        return if (isOracleJdbcUrl()) getHostnameFromOracleJdbcUrl() else uri!!.host
    }

    val port: Int get() {
        assertIsValid()
        return if (isOracleJdbcUrl()) getPortFromOracleJdbcUrl() else uri!!.givenOrDefaultPort()
    }

    val suffix: String get() {
        assertIsValid()
        return if (isOracleJdbcUrl()) {
            getSuffixFromOracleJdbcUrl()
        } else {
            uri!!.path + (if (uri.query != null) "?" + uri.query else "")
        }
    }

    private fun isOracleJdbcUrl() = url.startsWith("jdbc:oracle:thin:@")

    private fun getHostnameFromOracleJdbcUrl() = url
        .removeEverythingBeforeHostnameFromOracleJdbcUrl()
        .substringBefore('?')
        .substringBefore('/')
        .substringBefore(':')

    private fun getPortFromOracleJdbcUrl() = Regex("^[0-9]+")
        .find(
            url
                .removeEverythingBeforeHostnameFromOracleJdbcUrl()
                .removePrefix(getHostnameFromOracleJdbcUrl() + ":")
        )
        ?.value
        ?.toInt()
        ?: PortNumbers.DEFAULT_ORACLE_PORT

    private fun getSuffixFromOracleJdbcUrl() = url
        .removeEverythingBeforeHostnameFromOracleJdbcUrl()
        .removePrefix(getHostnameFromOracleJdbcUrl())
        .replace(Regex("^:[0-9]+"), "")

    private fun getProtocolFromOracleJdbcUrl() =
        Regex("(?<=^jdbc:oracle:thin:@)([a-z]+:\\/\\/|\\/\\/)").find(url)?.value ?: ""

    private fun isPostgresJdbcUrl() = url.startsWith("jdbc:postgresql:")

    fun isValid(): Boolean = when {
        isOracleJdbcUrl() -> getHostnameFromOracleJdbcUrl().isNotEmpty()
        uri != null -> !uri.scheme.isNullOrEmpty() && !uri.host.isNullOrEmpty()
        else -> false
    }

    fun assertIsValid() = if (!isValid()) {
        throw UrlValidationException("The format of the URL \"$url\" is not supported")
    } else { null }

    fun makeString(
        modifiedHostName: String = hostName,
        modifiedPort: Int = port,
        modifiedSuffix: String = suffix
    ): String {
        assertIsValid()
        val portWithColon = ":$modifiedPort"
        return when {
            isOracleJdbcUrl() -> "jdbc:oracle:thin:@${getProtocolFromOracleJdbcUrl()}$modifiedHostName$portWithColon$modifiedSuffix"
            isPostgresJdbcUrl() -> "jdbc:postgresql://$modifiedHostName$portWithColon$modifiedSuffix"
            else -> "${uri!!.scheme}://$modifiedHostName$portWithColon$modifiedSuffix"
        }
    }

    fun withModifiedHostName(newHostName: String) = Url(makeString(modifiedHostName = newHostName))

    fun withModifiedPort(newPort: Int) = Url(makeString(modifiedPort = newPort))
}

fun URI.givenOrDefaultPort() = if (port == -1) when (scheme) {
    "https" -> PortNumbers.HTTPS_PORT
    "postgresql" -> PortNumbers.DEFAULT_POSTGRES_PORT
    else -> PortNumbers.HTTP_PORT
} else port

fun String.removeEverythingBeforeHostnameFromOracleJdbcUrl() =
    replace(Regex("^jdbc:oracle:thin:@([a-z]+:\\/\\/|\\/\\/)?"), "")
