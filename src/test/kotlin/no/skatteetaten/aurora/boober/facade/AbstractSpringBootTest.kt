package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.token.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.bodyAsString
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.time.Instant

typealias MockRule = RecordedRequest.() -> MockResponse?
typealias MockFlag = RecordedRequest.() -> Boolean?

private val logger = KotlinLogging.logger { }

/*

  In order to mock http call use one of the mock methods
   - openShiftMock
   - skapMock
   - bitbucketMock
   - cantusMock
 */
abstract class AbstractSpringBootTest : ResourceLoader() {

    val auroraConfigRef = AuroraConfigRef("paas", "master", "123abb")

    @Value("\${integrations.openshift.port}")
    lateinit var ocpPort: String

    @Value("\${integrations.skap.port}")
    lateinit var skapPort: String

    @Value("\${integrations.dbh.port}")
    lateinit var dbhPort: String

    @Value("\${integrations.cantus.port}")
    lateinit var cantusPort: String

    @Value("\${integrations.bitbucket.port}")
    lateinit var bitbucketPort: String

    fun RecordedRequest.replayRequestJsonWithModification(
        rootPath: String,
        key: String,
        newValue: JsonNode
    ): MockResponse {
        val ad: JsonNode = jacksonObjectMapper().readTree(this.bodyAsString())
        (ad.at(rootPath) as ObjectNode).replace(key, newValue)

        return MockResponse()
            .setResponseCode(200)
            .setBody(jacksonObjectMapper().writeValueAsString(ad))
            .setHeader("Content-Type", MediaType.APPLICATION_JSON_UTF8_VALUE)
    }

    data class MockRules(
        val check: MockFlag,
        val fn: MockRule
    )

    class HttpMock {

        val mockRules: MutableList<MockRules> = mutableListOf()

        fun start(port: Int): MockWebServer {

            return MockWebServer().apply {
                dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val matchingRule = mockRules.asSequence().mapNotNull {
                            if (it.check(request) == true) {
                                it.fn(request)
                            } else null
                        }.firstOrNull()

                        if (matchingRule == null) {
                            logger.debug("No matching rules matches request=;request")
                            throw IllegalArgumentException("No function matches request=$request")
                        }

                        return matchingRule
                    }
                }
                start(port)
            }
        }

        fun rule(r: MockRules): HttpMock {
            mockRules.add(r)
            return this
        }

        /*
        Add a rule to this mock. If fn returns null the rule will be ignored
         */
        fun rule(fn: MockRule): HttpMock {
            mockRules.add(MockRules({ true }, fn))
            return this
        }

        /*
                  Record a rule in the mock. Add an optional check as the first parameter

                  If the body of the rule returns null it will be ignored.

                  The ordering of the rules matter, the first one that matches will be returned
                 */
        fun rule(check: MockFlag = { true }, fn: MockRule): HttpMock {
            mockRules.add(MockRules(check, fn))
            return this
        }

        companion object {
            var httpMocks: MutableList<MockWebServer> = mutableListOf()

            fun clearAllHttpMocks() {
                httpMocks.forEach {
                    it.shutdown()
                }
                httpMocks = mutableListOf()
            }
        }
    }

    fun httpMockServer(port: String, block: HttpMock.() -> Unit = {}): MockWebServer =
        httpMockServer(port.toInt(), block)

    fun httpMockServer(port: Int, block: HttpMock.() -> Unit = {}): MockWebServer {
        val instance = HttpMock()
        instance.block()
        val server = instance.start(port)
        HttpMock.httpMocks.add(server)
        return server
    }

    fun openShiftMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return httpMockServer(ocpPort.toInt(), block)
    }

    fun skapMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return httpMockServer(skapPort.toInt(), block)
    }

    fun bitbucketMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return httpMockServer(bitbucketPort.toInt(), block)
    }

    fun cantuMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return httpMockServer(cantusPort.toInt(), block)
    }

    fun dbhMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return httpMockServer(dbhPort.toInt(), block)
    }

    @MockkBean
    lateinit var userDetailsProvider: UserDetailsProvider

    @MockkBean
    lateinit var serviceAccountTokenProvider: ServiceAccountTokenProvider

    @AfterEach
    fun after() {
        HttpMock.clearAllHttpMocks()
    }

    fun mockJsonFromFile(fileName: String): MockResponse {
        return MockResponse()
            .setBody(loadBufferResource(fileName, DeployFacadeTest::class.java.simpleName))
            .setHeader("Content-Type", MediaType.APPLICATION_JSON_UTF8_VALUE)
    }

    @BeforeEach
    fun before() {
        Instants.determineNow = { Instant.EPOCH }
        every { userDetailsProvider.getAuthenticatedUser() } returns User(
            "hero", "token", "Jayne Cobb", grantedAuthorities = listOf(
                SimpleGrantedAuthority("APP_PaaS_utv"), SimpleGrantedAuthority("APP_PaaS_drift")
            )
        )
        every { serviceAccountTokenProvider.getToken() } returns "auth token"
    }
}

fun json(body: String) =
    MockResponse().setResponseCode(200)
        .setBody(body)
        .setHeader("Content-Type", MediaType.APPLICATION_JSON_UTF8_VALUE)

fun json(body: Any) =
    MockResponse().setResponseCode(200)
        .setBody(jacksonObjectMapper().writeValueAsString(body))
        .setHeader("Content-Type", MediaType.APPLICATION_JSON_UTF8_VALUE)
