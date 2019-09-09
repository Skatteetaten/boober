package no.skatteetaten.aurora.boober.unit.resourceprovisioning

import assertk.Assert
import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.messageContains
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.DatabaseInstance
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseEngine
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner.DbApiEnvelope
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaIdRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResults
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaRequestDetails
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaUser
import no.skatteetaten.aurora.boober.utils.ResourceLoader
import no.skatteetaten.aurora.boober.utils.jsonMapper
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestTemplate

class DatabaseSchemaProvisionerTest : ResourceLoader() {

    private val server = MockWebServer()
    private val baseUrl = server.url("/")

    val userDetailsProvider: UserDetailsProvider = mockk {
        every { getAuthenticatedUser() } returns User("username", "token")
    }

    val provisioner = DatabaseSchemaProvisioner(
        dbhUrl = baseUrl.toString(),
        restTemplate = RestTemplate(),
        userDetailsProvider = userDetailsProvider,
        mapper = jsonMapper()
    )

    val databaseInstance = DatabaseInstance(name = null, fallback = true, labels = mapOf("affiliation" to "aos"))
    val id = "fd59dba9-7d67-4ea2-bb98-081a5df8c387"
    val appName = "reference"
    val schemaName = "reference"

    val labels =
        mapOf("affiliation" to "aos", "environment" to "aos-utv", "application" to appName, "name" to schemaName)

    val details = SchemaRequestDetails(
        schemaName = schemaName,
        users = listOf(SchemaUser("SCHEMA", "a", "aos")),
        engine = DatabaseEngine.ORACLE,
        affiliation = "aos",
        databaseInstance = databaseInstance
    )

    @Test
    fun `Schema request with id succeeds when schema exists`() {

        server.execute(loadResource("schema_$id.json")) {
            val provisionResult = provisioner.provisionSchemas(
                listOf(
                    SchemaIdRequest(
                        id,
                        details
                    )
                )
            )
            assertThat(provisionResult).schemaIsCorrect()
        }
    }

    @Test
    fun `Schema request with id fails when schema does not exist`() {

        server.execute(404 to loadResource("schema_${id}_not_found.json")) {
            assertThat {
                provisioner.provisionSchemas(
                    listOf(
                        SchemaIdRequest(
                            id,
                            details
                        )
                    )
                )
            }.isFailure().all { isInstanceOf(ProvisioningException::class) }
        }
    }

    @Test
    fun `Matching of application coordinates to schema`() {
        val request = server.execute(loadResource("schema_$id.json")) {
            val provisionResult = provisioner.provisionSchemas(
                listOf(
                    SchemaForAppRequest(
                        "utv",
                        "reference",
                        true,
                        details
                    )
                )
            )
            assertThat(provisionResult).schemaIsCorrect()
        }.first()

        assertThat(request?.path).isNotNull()
            .contains("labels=affiliation%3Daos,environment%3Daos-utv,application%3Dreference,name%3Dreference")
        assertThat(request?.path).isNotNull().contains("roles=SCHEMA&engine=ORACLE")
    }

    @Test
    fun `Creates new schema if schema is missing`() {
        val responses = server.execute(
            DbApiEnvelope(""),
            loadResource("schema_$id.json")
        ) {
            provisioner.provisionSchemas(
                listOf(
                    SchemaForAppRequest(
                        "utv",
                        "reference",
                        true,
                        details
                    )
                )
            )
        }

        assertThat(responses[0]?.path).isNotNull()
            .contains("/api/v1/schema/?labels=affiliation%3Daos,environment%3Daos-utv,application%3Dreference,name%3Dreference&roles=SCHEMA&engine=ORACLE")
        assertThat(responses[1]?.path).isNotNull().contains("/api/v1/schema/")
    }

    @Test
    fun `Handle dbh exception when creating new schema`() {
        server.execute(
            200 to DbApiEnvelope(""),
            500 to """{"status":"Failed","totalCount":1,"items":["ORA-00059: maximum number of DB_FILES exceeded"]}"""
        ) {
            assertThat {
                provisioner.provisionSchemas(
                    listOf(SchemaForAppRequest("utv", "reference", true, details))
                )
                }.isNotNull().isFailure().messageContains("ORA-00059: maximum number of DB_FILES exceeded")
        }
    }

    @Test
    fun `Handle generic exception when creating new schema`() {
        server.execute(
            200 to DbApiEnvelope(""),
            500 to "{}"
        ) {
            assertThat {
                provisioner.provisionSchemas(
                    listOf(SchemaForAppRequest("utv", "reference", true, details))
                )
            }.isNotNull().isFailure().messageContains("Unable to create database schema")
        }
    }

    private fun Assert<SchemaProvisionResults>.schemaIsCorrect() = given { r ->
        val results = r.results
        assertThat(results.size).isEqualTo(1)
        val schema = results[0].dbhSchema
        assertThat(schema.jdbcUrl).isEqualTo("jdbc:oracle:thin:@some-db-server01.skead.no:1521/dbhotel")
        assertThat(schema.username).isEqualTo("VCLFVAPKGOMBCFTWEVKZDYBGVTMYDP")
        assertThat(schema.password).isEqualTo("yYGmRnUPBORxMoMcPptGvDYgKxmRSm")
    }
}
