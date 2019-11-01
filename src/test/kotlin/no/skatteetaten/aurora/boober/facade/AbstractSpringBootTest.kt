package no.skatteetaten.aurora.boober.facade

import com.ninjasquad.springmockk.MockkBean
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Value

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

    class HttpMock {

        val mockRules: MutableList<MockRule> = mutableListOf()

        fun start(port: Int): MockWebServer {

            return MockWebServer().apply {
                dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        return mockRules.toList().mapNotNull {
                            it(request)
                        }.firstOrNull() ?: throw IllegalArgumentException("No function matches request=$request")
                    }
                }
                start(port)
            }
        }

        fun rule(fn: MockRule): HttpMock {
            mockRules.add(fn)
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

    @MockkBean
    lateinit var userDetailsProvider: UserDetailsProvider

    var httpMocks: MutableList<MockWebServer> = mutableListOf()

    @AfterEach
    fun after() {
        httpMocks.forEach { it.shutdown() }
    }
}