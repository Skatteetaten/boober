package no.skatteetaten.aurora.boober.facade

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.token.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.authority.SimpleGrantedAuthority

typealias MockRule = (RecordedRequest) -> MockResponse?

abstract class AbstractSpringBootTest : ResourceLoader() {

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

    data class MockRules(
        val check: (RecordedRequest) -> Boolean,
        val fn: MockRule
    )
    class HttpMock {

        val mockRules: MutableList<MockRules> = mutableListOf()

        fun start(port: Int): MockWebServer {

            return MockWebServer().apply {
                dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        return mockRules.asSequence().mapNotNull {
                            if (it.check(request)) {
                                it.fn(request)
                            } else null
                        }.firstOrNull() ?: throw IllegalArgumentException("No function matches request=$request")
                    }
                }
                start(port)
            }
        }

        /*
          Record a rule in the mock. Add an optional check as the first parameter

          If the body of the rule returns null it will be ignored.

          The ordering of the rules matter, the first one that matches will be returned
         */
        fun rule(check: (RecordedRequest) -> Boolean = { true }, fn: MockRule): HttpMock {
            mockRules.add(MockRules(check, fn))
            return this
        }

        /*
        Add a rule to this mock. If fn returns null the rule will be ignored
         */
        fun rule(fn: MockRule): HttpMock {
            mockRules.add(MockRules({ true }, fn))
            return this
        }
    }

    fun mockWebServer(port: Int, block: HttpMock.() -> Unit = {}): MockWebServer {
        val instance = HttpMock()
        instance.block()
        val server = instance.start(port)
        httpMocks.add(server)
        return server
    }

    fun openShiftMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return mockWebServer(ocpPort.toInt(), block)
    }

    fun skapMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return mockWebServer(skapPort.toInt(), block)
    }

    fun bitbucketMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return mockWebServer(bitbucketPort.toInt(), block)
    }

    fun cantuMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return mockWebServer(cantusPort.toInt(), block)
    }

    fun dbhMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return mockWebServer(dbhPort.toInt(), block)
    }

    @MockkBean
    lateinit var userDetailsProvider: UserDetailsProvider

    @MockkBean
    lateinit var serviceAccountTokenProvider: ServiceAccountTokenProvider

    var httpMocks: MutableList<MockWebServer> = mutableListOf()

    @AfterEach
    fun after() {
        httpMocks.forEach { it.shutdown() }
    }

    @BeforeEach
    fun before() {
        every { userDetailsProvider.getAuthenticatedUser() } returns User(
            "hero", "token", "Jayne Cobb", grantedAuthorities = listOf(
                SimpleGrantedAuthority("APP_PaaS_utv"), SimpleGrantedAuthority("APP_PaaS_drift")
            ))
        every { serviceAccountTokenProvider.getToken() } returns "auth token"
    }
}
