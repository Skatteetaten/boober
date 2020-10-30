package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isSuccess
import assertk.assertions.messageContains
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.facade.json
import no.skatteetaten.aurora.boober.feature.DatabaseInstance
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseEngine
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaInstance
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbApiEnvelope
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhUser
import no.skatteetaten.aurora.boober.service.resourceprovisioning.RestorableSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaIdRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaRequestDetails
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.httpMockServer
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.web.client.RestTemplateBuilder
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class DatabaseSchemaProvisionerTest {
    val baseUrl = "http://localhost:5000"
    val dbhRestTemplate = DbhRestTemplateWrapper(RestTemplateBuilder().build(), baseUrl, 0)
    private var provisioner = DatabaseSchemaProvisioner(dbhRestTemplate, jacksonObjectMapper())

    @AfterEach
    fun after() {
        HttpMock.clearAllHttpMocks()
    }

    @Test
    fun `Should use schema in cooldown when no active found and tryReuse configured`() {
        val request = createSchemaForAppRequest(tryReuse = true, generate = true)
        val cooldownSchemaId = "cooldownID"
        mockDbh(request, activeSchemaId = null, cooldownSchemaId = cooldownSchemaId)

        assertThat {
            provisioner.provisionSchema(request)
        }.isSuccess().given {
            assertThat(it.dbhSchema.id).isEqualTo(cooldownSchemaId)
        }
    }

    @Test
    fun `When request for id and no active schema and tryReuse disabled should fail`() {
        val cooldownId = "cooldownId"
        val request = createSchemaIdRequest(tryReuse = false, id = cooldownId)
        mockDbh(request, activeSchemaId = null, cooldownSchemaId = cooldownId)

        assertThat {
            provisioner.provisionSchema(request)
        }.isFailure().messageContains("Expected dbh response to contain schema info")
    }

    @Test
    fun `When request for id and no active schema should use cooldown schema and tryReuse configured`() {
        val cooldownId = "cooldownId"
        val request = createSchemaIdRequest(tryReuse = true, id = cooldownId)
        mockDbh(request, activeSchemaId = null, cooldownSchemaId = cooldownId)

        assertThat {
            provisioner.provisionSchema(request)
        }.isSuccess().given {
            assertThat(it.dbhSchema.id).isEqualTo(cooldownId)
        }
    }

    @Test
    fun `When request for id and active schema should use active schema when found and tryReuse configured`() {
        val activeSchemaId = "activeId"
        val request = createSchemaIdRequest(tryReuse = true, id = activeSchemaId)
        mockDbh(request, activeSchemaId = activeSchemaId, cooldownSchemaId = "cooldownId")

        assertThat {
            provisioner.provisionSchema(request)
        }.isSuccess().given {
            assertThat(it.dbhSchema.id).isEqualTo(activeSchemaId)
        }
    }

    @Test
    fun `Should use active schema if present when tryReuse configured`() {
        val request = createSchemaForAppRequest(tryReuse = true, generate = true)
        val activeSchemaId = "activeId"
        mockDbh(request, activeSchemaId = activeSchemaId, cooldownSchemaId = "cooldownId")

        assertThat {
            provisioner.provisionSchema(request)
        }.isSuccess().given {
            assertThat(it.dbhSchema.id).isEqualTo(activeSchemaId)
        }
    }

    @Test
    fun `Should generate new schema when no active schema and no cooldown schema when tryReuse configured`() {
        val request = createSchemaForAppRequest(tryReuse = true, generate = true)
        mockDbh(request, activeSchemaId = null, cooldownSchemaId = null)

        assertThat {
            provisioner.provisionSchema(request)
        }.isSuccess()
    }

    @Test
    fun `Should generate new schema when tryReuse is not configured and no active schema`() {
        val request = createSchemaForAppRequest(tryReuse = false, generate = true)
        mockDbh(request, activeSchemaId = null, cooldownSchemaId = null)

        assertThat {
            provisioner.provisionSchema(request)
        }.isSuccess()
    }

    @Test
    fun `Should fail when no active or cooldown schema and generate false`() {
        val request = createSchemaForAppRequest(tryReuse = true, generate = false)
        mockDbh(request, activeSchemaId = null, cooldownSchemaId = null)

        assertThat {
            provisioner.provisionSchema(request)
        }.isFailure().messageContains("Could not find schema")
    }

    @Test
    fun `Should throw when findSchema fails for SchemaForAppRequest`() {
        val request = createSchemaForAppRequest(tryReuse = false, generate = false)
        mockDbh(request, "activeSchema", null, FailRequest("GET", "schema/"))

        assertThat {
            provisioner.provisionSchema(request)
        }.isFailure().messageContains("Unable to get database schema")
    }

    @Test
    fun `Should throw when findSchema fails for SchemaIdRequest`() {
        val request = createSchemaIdRequest(id = "activeSchema", tryReuse = false)
        mockDbh(request, "activeSchema", null, FailRequest("GET", "schema/"))

        assertThat {
            provisioner.provisionSchema(request)
        }.isFailure().messageContains("Unable to get database schema")
    }

    @Test
    fun `Should throw when createSchema fails for SchemaForAppRequest`() {
        val request = createSchemaForAppRequest(generate = true, tryReuse = false)
        mockDbh(request, null, null, FailRequest("POST", "schema/"))

        assertThat {
            provisioner.provisionSchema(request)
        }.isFailure().messageContains("Unable to create database schema")
    }

    @Test
    fun `Should continue when findSchemaInCooldown fails for SchemaForAppRequest`() {
        val request = createSchemaForAppRequest(generate = true, tryReuse = true)
        mockDbh(request, null, null, FailRequest("GET", "restorableSchema/"))

        assertThat {
            provisioner.provisionSchema(request)
        }.isSuccess()
    }

    @Test
    fun `Should throw when findSchemaInCooldown fails for SchemaIdRequest`() {
        val request = createSchemaIdRequest(id = "activeId", tryReuse = true)
        mockDbh(request, null, null, FailRequest("GET", "restorableSchema/"))

        assertThat {
            provisioner.provisionSchema(request)
        }.isFailure().messageContains("Expected dbh response to contain schema info")
    }

    @Test
    fun `Should throw when activateSchema fails`() {
        val request = createSchemaIdRequest(id = "cooldownSchemaId", tryReuse = true)
        mockDbh(request, null, "cooldownSchemaId", FailRequest("PATCH", "restorableSchema/"))

        assertThat {
            provisioner.provisionSchema(request)
        }.isFailure().messageContains("Unable to reactivate schema")
    }

    private fun createSchemaIdRequest(id: String, tryReuse: Boolean) = SchemaIdRequest(
        id = id,
        details = SchemaRequestDetails("schemaname", DatabaseEngine.POSTGRES, "paas", DatabaseInstance("")),
        tryReuse = tryReuse

    )

    private fun createSchemaForAppRequest(generate: Boolean, tryReuse: Boolean) = SchemaForAppRequest(
        environment = "env",
        application = "app",
        generate = generate,
        user = User("username", "token"),
        details = SchemaRequestDetails("schemaname", DatabaseEngine.POSTGRES, "paas", DatabaseInstance("")),
        tryReuse = tryReuse

    )

    fun createRestorableSchema(id: String) = RestorableSchema(
        0,
        0,
        createDbhSchema(id)
    )

    fun createDbhSchema(
        id: String,
        labels: Map<String, String> = mapOf(
            "affiliation" to "paas",
            "environment" to "env",
            "application" to "myApplication",
            "name" to "schemaname",
            "userId" to "username"
        )

    ) = DbhSchema(
        id = id,
        type = "MANAGED",
        databaseInstance = DatabaseSchemaInstance(0, "host"),
        jdbcUrl = "jdbcUrl",
        labels = labels,
        users = listOf(
            DbhUser("username", "password", "schema")
        )
    )

    fun mockDbh(
        request: SchemaProvisionRequest,
        activeSchemaId: String? = UUID.randomUUID().toString(),
        cooldownSchemaId: String? = null,
        failRequest: FailRequest? = null
    ) {

        httpMockServer(5000) {
            if (failRequest != null) {
                rule({
                    method == failRequest.method && path.contains(failRequest.path)
                }) {
                    MockResponse().setResponseCode(500)
                }
            }

            val forAppOrIdPath = when (request) {
                is SchemaIdRequest -> request.id
                is SchemaForAppRequest -> "?labels"
            }

            rule({
                path.contains("restorableSchema/$forAppOrIdPath") && method == "GET"
            }) {
                json(DbApiEnvelope("ok", cooldownSchemaId?.let { listOf(createRestorableSchema(it)) } ?: emptyList()))
            }

            rule({
                method == "PATCH" && path.contains("restorableSchema")
            }) {
                json(DbApiEnvelope("ok", cooldownSchemaId?.let { listOf(createDbhSchema(it)) } ?: emptyList()))
            }

            rule({
                method == "POST" && path.contains("schema/")
            }) {
                json(DbApiEnvelope("ok", listOf(createDbhSchema(UUID.randomUUID().toString()))))
            }

            rule {
                json(DbApiEnvelope("ok", activeSchemaId?.let { listOf(createDbhSchema(it)) } ?: emptyList()))
            }
        }
    }

    data class FailRequest(val method: String, val path: String)
}
