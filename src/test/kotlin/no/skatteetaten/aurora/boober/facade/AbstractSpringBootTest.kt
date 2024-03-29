package no.skatteetaten.aurora.boober.facade

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.ApplicationDeploymentHerkimer
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.HerkimerResponse
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.objectMapperWithTime
import no.skatteetaten.aurora.boober.service.openshift.token.ServiceAccountTokenProvider
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.bodyAsString
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.httpMockServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.time.Instant
import java.time.LocalDateTime

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

    @Value("\${integrations.herkimer.port}")
    lateinit var herkimerPort: String

    @Value("\${integrations.fiona.port}")
    lateinit var fionaPort: String

    @Value("\${integrations.mattermost.port}")
    lateinit var mattermostPort: String

    fun RecordedRequest.replayRequestJsonWithModification(
        rootPath: String,
        key: String,
        newValue: JsonNode
    ): MockResponse {
        val ad: JsonNode = jacksonObjectMapper().readTree(this.bodyAsString())
        (ad.at(rootPath) as ObjectNode).let {
            try {
                it.replace(key, newValue)
            } catch (e: Exception) {
                it.set<JsonNode>(key, newValue)
            }
        }

        return MockResponse()
            .setResponseCode(200)
            .setBody(jacksonObjectMapper().writeValueAsString(ad))
            .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
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

    fun cantusMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return httpMockServer(cantusPort.toInt(), block)
    }

    fun dbhMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return httpMockServer(dbhPort.toInt(), block)
    }

    fun fionaMock(block: HttpMock.() -> Unit = {}): MockWebServer {
        return httpMockServer(fionaPort.toInt(), block)
    }

    fun mattermostMock(block: HttpMock.() -> Unit = {}): MockWebServer =
        httpMockServer(mattermostPort.toInt(), block)

    fun herkimerMock(block: HttpMock.() -> Unit = {}): MockWebServer =
        httpMockServer(herkimerPort.toInt(), block)

    fun applicationDeploymentGenerationMock(
        adId: String = "1234567890",
        also: HttpMock.() -> Unit = {}
    ): MockWebServer {
        val ad = ApplicationDeploymentHerkimer(
            id = adId,
            name = "name",
            environmentName = "env",
            cluster = "cluster",
            businessGroup = "aurora",
            createdDate = LocalDateTime.now(),
            modifiedDate = LocalDateTime.now(),
            createdBy = "aurora",
            modifiedBy = "aurora"
        )

        return herkimerMock {
            rule({
                path!!.contains("applicationDeployment")
            }) {
                json(HerkimerResponse(items = listOf(ad)))
            }
            also()
        }
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
        val folder = "$packageName/DeployFacadeTest"
        return MockResponse()
            .setBody(loadBufferResource(fileName, folder = folder))
            .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
    }

    @BeforeEach
    fun before() {
        Instants.determineNow = { Instant.EPOCH }
        every { userDetailsProvider.getAuthenticatedUser() } returns User(
            "hero", "token", "Jayne Cobb",
            grantedAuthorities = listOf(
                SimpleGrantedAuthority("APP_PaaS_utv"), SimpleGrantedAuthority("APP_PaaS_drift")
            )
        )
        every { serviceAccountTokenProvider.getToken() } returns "auth token"
    }

    private fun HttpMock.basicRule(
        pathPostfix: String,
        jsonFile: String? = null,
        statusCode: Int = HttpStatus.OK.value(),
        httpMethod: String = "GET"
    ): HttpMock =
        rule({ method == httpMethod && path?.endsWith(pathPostfix) == true }) {
            (jsonFile?.let { mockJsonFromFile(jsonFile) } ?: MockResponse()).apply {
                setResponseCode(statusCode)
            }
        }

    fun HttpMock.getRule(pathPostfix: String, jsonFile: String? = null, statusCode: Int = HttpStatus.OK.value()): HttpMock =
        basicRule(pathPostfix, jsonFile, statusCode, "GET")

    fun HttpMock.postRule(pathPostfix: String, jsonFile: String? = null, statusCode: Int = HttpStatus.OK.value()): HttpMock =
        basicRule(pathPostfix, jsonFile, statusCode, "POST")

    fun HttpMock.putRule(pathPostfix: String, jsonFile: String? = null, statusCode: Int = HttpStatus.OK.value()): HttpMock =
        basicRule(pathPostfix, jsonFile, statusCode, "PUT")
}

fun json(body: String, responseCode: Int = 200) =
    MockResponse().setResponseCode(responseCode)
        .setBody(body)
        .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)

fun json(body: Any) =
    MockResponse().setResponseCode(200)
        .setBody(objectMapperWithTime.writeValueAsString(body))
        .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
