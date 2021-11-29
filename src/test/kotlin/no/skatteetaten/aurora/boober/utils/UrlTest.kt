package no.skatteetaten.aurora.boober.utils

import no.skatteetaten.aurora.boober.service.UrlValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UrlTest {

    // Test data

    private val httpUrl = "http://hostname"
    private val httpUrlWithPort = "http://hostname:6543"
    private val httpUrlWithPath = "http://hostname/path/secondPath"
    private val httpUrlWithSlash = "http://hostname/"
    private val httpUrlWithQuery = "http://hostname?param1=some_arg&param2=another_arg"
    private val httpUrlWithPortPathSlashAndQuery = "http://hostname:6543/path/?param=some_arg"

    private val httpsUrl = "https://hostname"

    private val jdbcPostgresUrl = "jdbc:postgresql://hostname/dbname"
    private val jdbcPostgresUrlWithoutHostname = "jdbc:postgresql:dbname"
    private val jdbcPostgresUrlWithPort = "jdbc:postgresql://hostname:6543/dbname"
    private val jdbcPostgresUrlWithQuery = "jdbc:postgresql://hostname/dbname?param1=some_arg&param2=another_arg"
    private val jdbcPostgresUrlWithPortAndQuery = "jdbc:postgresql://hostname:6543/dbname?param=some_arg"

    private val jdbcOracleUrl = "jdbc:oracle:thin:@hostname"
    private val jdbcOracleUrlWithPort = "jdbc:oracle:thin:@hostname:6543"
    private val jdbcOracleUrlWithService = "jdbc:oracle:thin:@hostname/service"
    private val jdbcOracleUrlWithMode = "jdbc:oracle:thin:@hostname:mode"
    private val jdbcOracleUrlWithQuery = "jdbc:oracle:thin:@hostname?param1=some_arg&param2=another_arg"
    private val jdbcOracleUrlWithPortAndService = "jdbc:oracle:thin:@hostname:6543/service"
    private val jdbcOracleUrlWithPortAndMode = "jdbc:oracle:thin:@hostname:6543:mode"
    private val jdbcOracleUrlWithPortServiceAndMode = "jdbc:oracle:thin:@hostname:6543/service:mode"
    private val jdbcOracleUrlWithPortServiceModeAndQuery = "jdbc:oracle:thin:@hostname:6543/service:mode?param=arg"
    private val jdbcOracleUrlWithDoubleSlash = "jdbc:oracle:thin:@//hostname"
    private val jdbcOracleUrlWithProtocol = "jdbc:oracle:thin:@protocol://hostname"
    private val jdbcOracleUrlWithProtocolPortServiceModeAndQuery = "jdbc:oracle:thin:@protocol://hostname:6543/service:mode?param=arg"

    private val httpUrls = listOf(
        httpUrl,
        httpUrlWithPort,
        httpUrlWithPath,
        httpUrlWithSlash,
        httpUrlWithQuery,
        httpUrlWithPortPathSlashAndQuery
    )

    private val httpsUrls = listOf(httpsUrl)

    private val jdbcPosgresUrls = listOf(
        jdbcPostgresUrl,
        jdbcPostgresUrlWithoutHostname,
        jdbcPostgresUrlWithPort,
        jdbcPostgresUrlWithQuery,
        jdbcPostgresUrlWithPortAndQuery
    )

    private val jdbcOracleUrls = listOf(
        jdbcOracleUrl,
        jdbcOracleUrlWithPort,
        jdbcOracleUrlWithService,
        jdbcOracleUrlWithMode,
        jdbcOracleUrlWithQuery,
        jdbcOracleUrlWithPortAndService,
        jdbcOracleUrlWithPortAndMode,
        jdbcOracleUrlWithPortServiceAndMode,
        jdbcOracleUrlWithPortServiceModeAndQuery,
        jdbcOracleUrlWithDoubleSlash,
        jdbcOracleUrlWithProtocol,
        jdbcOracleUrlWithProtocolPortServiceModeAndQuery
    )

    private val validUrls = listOf(
        httpUrls,
        httpsUrls,
        jdbcPosgresUrls,
        jdbcOracleUrls
    ).flatten()

    private val misspelledHttpUrl = "http:/hostname"
    private val misspelledJdbcPostgresUrl = "jdbc:postgres/hostname/dbname"
    private val misspelledJdbcOracleUrl = "jdbc:oracle:thing:@hostname"

    @Test
    fun `Validation works as expected`() {

        validUrls.forEach {
            with(Url(it)) {
                assertTrue { this.isValid() }
                this.assertIsValid()
            }
        }

        listOf(
            misspelledHttpUrl,
            misspelledJdbcPostgresUrl,
            misspelledJdbcOracleUrl
        ).forEach {
            with(Url(it)) {
                assertFalse(this.isValid())
                assertThrows<UrlValidationException> { this.assertIsValid() }
            }
        }
    }

    @Test
    fun `Hostname can be extracted from url`() {
        validUrls.forEach {
            assertEquals(
                if (it.contains("hostname")) "hostname" else "localhost",
                Url(it).hostName
            )
        }
    }

    @Test
    fun `Port can be extracted from url`() {
        httpUrls.forEach {
            assertEquals(
                if (it.contains("6543")) 6543 else 80,
                Url(it).port
            )
        }
        httpsUrls.forEach {
            assertEquals(
                if (it.contains("6543")) 6543 else 443,
                Url(it).port
            )
        }
        jdbcPosgresUrls.forEach {
            assertEquals(
                if (it.contains("6543")) 6543 else 5432,
                Url(it).port
            )
        }
        jdbcOracleUrls.forEach {
            assertEquals(
                if (it.contains("6543")) 6543 else 1521,
                Url(it).port
            )
        }
    }

    @Test
    fun `Suffix can be extracted from url`() {
        mapOf(
            httpUrl to "",
            httpUrlWithPort to "",
            httpUrlWithPath to "/path/secondPath",
            httpUrlWithSlash to "/",
            httpUrlWithQuery to "?param1=some_arg&param2=another_arg",
            httpUrlWithPortPathSlashAndQuery to "/path/?param=some_arg",
            httpsUrl to "",
            jdbcPostgresUrl to "/dbname",
            jdbcPostgresUrlWithoutHostname to "/dbname",
            jdbcPostgresUrlWithPort to "/dbname",
            jdbcPostgresUrlWithQuery to "/dbname?param1=some_arg&param2=another_arg",
            jdbcPostgresUrlWithPortAndQuery to "/dbname?param=some_arg",
            jdbcOracleUrl to "",
            jdbcOracleUrlWithPort to "",
            jdbcOracleUrlWithService to "/service",
            jdbcOracleUrlWithMode to ":mode",
            jdbcOracleUrlWithQuery to "?param1=some_arg&param2=another_arg",
            jdbcOracleUrlWithPortAndService to "/service",
            jdbcOracleUrlWithPortAndMode to ":mode",
            jdbcOracleUrlWithPortServiceAndMode to "/service:mode",
            jdbcOracleUrlWithPortServiceModeAndQuery to "/service:mode?param=arg",
            jdbcOracleUrlWithDoubleSlash to "",
            jdbcOracleUrlWithProtocol to "",
            jdbcOracleUrlWithProtocolPortServiceModeAndQuery to "/service:mode?param=arg"
        ).forEach { (input, expectedOutput) -> assertEquals(expectedOutput, Url(input).suffix) }
    }

    @Test
    fun `Generates string with default values`() {
        mapOf(
            httpUrl to "http://hostname:80",
            httpUrlWithPort to "http://hostname:6543",
            httpUrlWithPath to "http://hostname:80/path/secondPath",
            httpUrlWithSlash to "http://hostname:80/",
            httpUrlWithQuery to "http://hostname:80?param1=some_arg&param2=another_arg",
            httpUrlWithPortPathSlashAndQuery to "http://hostname:6543/path/?param=some_arg",
            httpsUrl to "https://hostname:443",
            jdbcPostgresUrl to "jdbc:postgresql://hostname:5432/dbname",
            jdbcPostgresUrlWithoutHostname to "jdbc:postgresql://localhost:5432/dbname",
            jdbcPostgresUrlWithPort to "jdbc:postgresql://hostname:6543/dbname",
            jdbcPostgresUrlWithQuery to "jdbc:postgresql://hostname:5432/dbname?param1=some_arg&param2=another_arg",
            jdbcPostgresUrlWithPortAndQuery to "jdbc:postgresql://hostname:6543/dbname?param=some_arg",
            jdbcOracleUrl to "jdbc:oracle:thin:@hostname:1521",
            jdbcOracleUrlWithPort to "jdbc:oracle:thin:@hostname:6543",
            jdbcOracleUrlWithService to "jdbc:oracle:thin:@hostname:1521/service",
            jdbcOracleUrlWithMode to "jdbc:oracle:thin:@hostname:1521:mode",
            jdbcOracleUrlWithQuery to "jdbc:oracle:thin:@hostname:1521?param1=some_arg&param2=another_arg",
            jdbcOracleUrlWithPortAndService to "jdbc:oracle:thin:@hostname:6543/service",
            jdbcOracleUrlWithPortAndMode to "jdbc:oracle:thin:@hostname:6543:mode",
            jdbcOracleUrlWithPortServiceAndMode to "jdbc:oracle:thin:@hostname:6543/service:mode",
            jdbcOracleUrlWithPortServiceModeAndQuery to "jdbc:oracle:thin:@hostname:6543/service:mode?param=arg",
            jdbcOracleUrlWithDoubleSlash to "jdbc:oracle:thin:@//hostname:1521",
            jdbcOracleUrlWithProtocol to "jdbc:oracle:thin:@protocol://hostname:1521",
            jdbcOracleUrlWithProtocolPortServiceModeAndQuery to "jdbc:oracle:thin:@protocol://hostname:6543/service:mode?param=arg"
        ).forEach { (input, expectedOutput) -> assertEquals(expectedOutput, Url(input).makeString()) }
    }

    @Test
    fun `Generates url with modified host name`() {

        validUrls.forEach {
            assertEquals(
                "newName.domain",
                Url(it).modifyHostName("newName.domain").hostName
            )
        }

        mapOf(
            httpUrl to "http://newName.domain:80",
            httpUrlWithPort to "http://newName.domain:6543",
            httpUrlWithPath to "http://newName.domain:80/path/secondPath",
            httpUrlWithSlash to "http://newName.domain:80/",
            httpUrlWithQuery to "http://newName.domain:80?param1=some_arg&param2=another_arg",
            httpUrlWithPortPathSlashAndQuery to "http://newName.domain:6543/path/?param=some_arg",
            httpsUrl to "https://newName.domain:443",
            jdbcPostgresUrl to "jdbc:postgresql://newName.domain:5432/dbname",
            jdbcPostgresUrlWithoutHostname to "jdbc:postgresql://newName.domain:5432/dbname",
            jdbcPostgresUrlWithPort to "jdbc:postgresql://newName.domain:6543/dbname",
            jdbcPostgresUrlWithQuery to "jdbc:postgresql://newName.domain:5432/dbname?param1=some_arg&param2=another_arg",
            jdbcPostgresUrlWithPortAndQuery to "jdbc:postgresql://newName.domain:6543/dbname?param=some_arg",
            jdbcOracleUrl to "jdbc:oracle:thin:@newName.domain:1521",
            jdbcOracleUrlWithPort to "jdbc:oracle:thin:@newName.domain:6543",
            jdbcOracleUrlWithService to "jdbc:oracle:thin:@newName.domain:1521/service",
            jdbcOracleUrlWithMode to "jdbc:oracle:thin:@newName.domain:1521:mode",
            jdbcOracleUrlWithQuery to "jdbc:oracle:thin:@newName.domain:1521?param1=some_arg&param2=another_arg",
            jdbcOracleUrlWithPortAndService to "jdbc:oracle:thin:@newName.domain:6543/service",
            jdbcOracleUrlWithPortAndMode to "jdbc:oracle:thin:@newName.domain:6543:mode",
            jdbcOracleUrlWithPortServiceAndMode to "jdbc:oracle:thin:@newName.domain:6543/service:mode",
            jdbcOracleUrlWithPortServiceModeAndQuery to "jdbc:oracle:thin:@newName.domain:6543/service:mode?param=arg",
            jdbcOracleUrlWithDoubleSlash to "jdbc:oracle:thin:@//newName.domain:1521",
            jdbcOracleUrlWithProtocol to "jdbc:oracle:thin:@protocol://newName.domain:1521",
            jdbcOracleUrlWithProtocolPortServiceModeAndQuery to "jdbc:oracle:thin:@protocol://newName.domain:6543/service:mode?param=arg"
        ).forEach {
            (input, expectedOutput) ->
            assertEquals(
                expectedOutput,
                Url(input).modifyHostName("newName.domain").makeString()
            )
        }
    }

    @Test
    fun `Generates url with modified port`() {

        validUrls.forEach {
            assertEquals(
                18000,
                Url(it).modifyPort(18000).port
            )
        }

        mapOf(
            httpUrl to "http://hostname:18000",
            httpUrlWithPort to "http://hostname:18000",
            httpUrlWithPath to "http://hostname:18000/path/secondPath",
            httpUrlWithSlash to "http://hostname:18000/",
            httpUrlWithQuery to "http://hostname:18000?param1=some_arg&param2=another_arg",
            httpUrlWithPortPathSlashAndQuery to "http://hostname:18000/path/?param=some_arg",
            httpsUrl to "https://hostname:18000",
            jdbcPostgresUrl to "jdbc:postgresql://hostname:18000/dbname",
            jdbcPostgresUrlWithoutHostname to "jdbc:postgresql://localhost:18000/dbname",
            jdbcPostgresUrlWithPort to "jdbc:postgresql://hostname:18000/dbname",
            jdbcPostgresUrlWithQuery to "jdbc:postgresql://hostname:18000/dbname?param1=some_arg&param2=another_arg",
            jdbcPostgresUrlWithPortAndQuery to "jdbc:postgresql://hostname:18000/dbname?param=some_arg",
            jdbcOracleUrl to "jdbc:oracle:thin:@hostname:18000",
            jdbcOracleUrlWithPort to "jdbc:oracle:thin:@hostname:18000",
            jdbcOracleUrlWithService to "jdbc:oracle:thin:@hostname:18000/service",
            jdbcOracleUrlWithMode to "jdbc:oracle:thin:@hostname:18000:mode",
            jdbcOracleUrlWithQuery to "jdbc:oracle:thin:@hostname:18000?param1=some_arg&param2=another_arg",
            jdbcOracleUrlWithPortAndService to "jdbc:oracle:thin:@hostname:18000/service",
            jdbcOracleUrlWithPortAndMode to "jdbc:oracle:thin:@hostname:18000:mode",
            jdbcOracleUrlWithPortServiceAndMode to "jdbc:oracle:thin:@hostname:18000/service:mode",
            jdbcOracleUrlWithPortServiceModeAndQuery to "jdbc:oracle:thin:@hostname:18000/service:mode?param=arg",
            jdbcOracleUrlWithDoubleSlash to "jdbc:oracle:thin:@//hostname:18000",
            jdbcOracleUrlWithProtocol to "jdbc:oracle:thin:@protocol://hostname:18000",
            jdbcOracleUrlWithProtocolPortServiceModeAndQuery to "jdbc:oracle:thin:@protocol://hostname:18000/service:mode?param=arg"
        ).forEach {
            (input, expectedOutput) ->
            assertEquals(
                expectedOutput,
                Url(input).modifyPort(18000).makeString()
            )
        }
    }
}
