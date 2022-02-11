package no.skatteetaten.aurora.boober.utils

import no.skatteetaten.aurora.boober.model.PortNumbers
import no.skatteetaten.aurora.boober.service.UrlValidationException
import java.net.URI

class UrlParser(val url: String) {

    private val uri: URI? = if (isJdbcUrl()) null else URI(url)

    private val jdbcUri: JdbcUri? = when {
        isPostgresJdbcUrl() -> PostgresJdbcUri(url)
        isOracleJdbcUrl() -> OracleJdbcUri(url)
        else -> null
    }

    val hostName: String get() {
        assertIsValid()
        return uri?.host ?: jdbcUri!!.hostName
    }

    val port: Int get() {
        assertIsValid()
        return uri?.givenOrDefaultPort() ?: jdbcUri!!.port
    }

    val suffix: String get() {
        assertIsValid()
        return uri?.suffix() ?: jdbcUri!!.suffix
    }

    private fun isJdbcUrl() = url.startsWith("jdbc:")

    private fun isOracleJdbcUrl() = url.startsWith("jdbc:oracle:thin:@")

    private fun isPostgresJdbcUrl() = url.startsWith("jdbc:postgresql:")

    fun isValid(): Boolean = (if (isJdbcUrl()) jdbcUri?.isValid() else uri?.isValid()) ?: false

    fun assertIsValid() = if (!isValid()) {
        throw UrlValidationException("The format of the URL \"$url\" is not supported")
    } else { null }

    fun makeString(
        modifiedHostName: String = hostName,
        modifiedPort: Int = port,
        modifiedSuffix: String = suffix
    ): String {
        assertIsValid()
        return if (isJdbcUrl()) {
            jdbcUri!!.makeString(modifiedHostName, modifiedPort, modifiedSuffix)
        } else {
            uri!!.makeString(modifiedHostName, modifiedPort, modifiedSuffix)
        }
    }

    fun withModifiedHostName(newHostName: String) = UrlParser(makeString(modifiedHostName = newHostName))

    fun withModifiedPort(newPort: Int) = UrlParser(makeString(modifiedPort = newPort))
}

private abstract class JdbcUri {
    abstract val hostName: String
    abstract val port: Int
    abstract val suffix: String

    abstract fun isValid(): Boolean

    abstract fun makeString(
        modifiedHostName: String = hostName,
        modifiedPort: Int = port,
        modifiedSuffix: String = suffix
    ): String
}

private class PostgresJdbcUri(postgresJdbcUrl: String) : JdbcUri() {

    val uri = URI(
        if (postgresJdbcUrl.startsWith("jdbc:postgresql://")) {
            postgresJdbcUrl
        } else {
            // If hostname is not given, it should default to localhost
            postgresJdbcUrl.replaceFirst("sql:", "sql://localhost/")
        }.removePrefix("jdbc:")
    )

    override val hostName: String get() = uri.host

    override val port: Int get() = uri.givenOrDefaultPort()

    override val suffix: String get() = uri.suffix()

    override fun isValid(): Boolean = uri.isValid()

    override fun makeString(
        modifiedHostName: String,
        modifiedPort: Int,
        modifiedSuffix: String
    ) = "jdbc:postgresql://$modifiedHostName:$modifiedPort$modifiedSuffix"
}

private class OracleJdbcUri(private val oracleJdbcUrl: String) : JdbcUri() {

    override val hostName: String get() = oracleJdbcUrl
        .removeEverythingBeforeHostnameFromOracleJdbcUrl()
        .substringBefore('?')
        .substringBefore('/')
        .substringBefore(':')

    override val port: Int get() = Regex("^[0-9]+")
        .find(
            oracleJdbcUrl
                .removeEverythingBeforeHostnameFromOracleJdbcUrl()
                .removePrefix("$hostName:")
        )
        ?.value
        ?.toInt()
        ?: PortNumbers.DEFAULT_ORACLE_PORT

    override val suffix: String get() = oracleJdbcUrl
        .removeEverythingBeforeHostnameFromOracleJdbcUrl()
        .removePrefix(hostName)
        .replace(Regex("^:[0-9]+"), "")

    val protocol: String get() =
        Regex("(?<=^jdbc:oracle:thin:@)([a-z]+:\\/\\/|\\/\\/)").find(oracleJdbcUrl)?.value ?: ""

    override fun isValid(): Boolean = hostName.isNotEmpty()

    override fun makeString(
        modifiedHostName: String,
        modifiedPort: Int,
        modifiedSuffix: String
    ) = "jdbc:oracle:thin:@$protocol$modifiedHostName:$modifiedPort$modifiedSuffix"

    private fun String.removeEverythingBeforeHostnameFromOracleJdbcUrl() =
        replace(Regex("^jdbc:oracle:thin:@([a-z]+:\\/\\/|\\/\\/)?"), "")
}

private fun URI.givenOrDefaultPort() = if (port == -1) when (scheme) {
    "https" -> PortNumbers.HTTPS_PORT
    "postgresql" -> PortNumbers.DEFAULT_POSTGRES_PORT
    else -> PortNumbers.HTTP_PORT
} else port

private fun URI.suffix() = path + (if (query != null) "?$query" else "")

private fun URI.isValid() = !scheme.isNullOrEmpty() && !host.isNullOrEmpty()

private fun URI.makeString(
    modifiedHostName: String,
    modifiedPort: Int,
    modifiedSuffix: String
) = "$scheme://$modifiedHostName:$modifiedPort$modifiedSuffix"
