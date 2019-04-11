package no.skatteetaten.aurora.boober.unit.resourceprovisioning

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.DatabaseInstance
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseEngine
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
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
            assertSchemaIsCorrect(provisionResult)
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
            }.thrownError { isInstanceOf(ProvisioningException::class) }
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
            assertSchemaIsCorrect(provisionResult)
        }.first()

        assertThat(request.path).contains("labels=affiliation%3Daos,environment%3Daos-utv,application%3Dreference,name%3Dreference")
        assertThat(request.path).contains("roles=SCHEMA&engine=ORACLE")

        /*
        def labelsString = labels . collect { k, v -> "$k%3D$v" }.join(",")
        dbhServer.expect(requestTo("${DBH_HOST}/api/v1/schema/?labels=$labelsString&roles=SCHEMA&engine=ORACLE"))
            .andRespond(withSuccess(loadResource("schema_${id}.json"), MediaType.APPLICATION_JSON))
            */
    }

    @Test
    fun `Creates new schema if schema is missing`() {
/*
        def labelsString = labels . collect { k, v -> "$k%3D$v" }.join(",")
        def createBody = new SchemaRequestPayload(
            labels + [userId: "aurora"],
        details.users,
        details.engine,
        details.databaseInstance.labels,
        details.databaseInstance.name,
        details.databaseInstance.fallback)

        def body = new ObjectMapper().writeValueAsString(createBody)
        dbhServer.expect(requestTo("${DBH_HOST}/api/v1/schema/?labels=$labelsString&roles=SCHEMA&engine=ORACLE"))
            .andRespond(withSuccess(loadResource("schema_empty_response.json"), MediaType.APPLICATION_JSON))

        dbhServer.expect(requestTo("${DBH_HOST}/api/v1/schema/")).andExpect(method(HttpMethod.POST))
            .andExpect(content().string(body))
            .andRespond(withSuccess(loadResource("schema_${id}.json"), MediaType.APPLICATION_JSON))


        def provisionResult = provisioner .
        provisionSchemas([new SchemaForAppRequest ("utv", "reference", true, details)])

        assertSchemaIsCorrect(provisionResult)*/
    }

    fun assertSchemaIsCorrect(provisionResult: SchemaProvisionResults) {

        val results = provisionResult.results
        assertThat(results.size).isEqualTo(1)
        val schema = results[0].dbhSchema
        assertThat(schema.jdbcUrl).isEqualTo("jdbc:oracle:thin:@some-db-server01.skead.no:1521/dbhotel")
        assertThat(schema.username).isEqualTo("VCLFVAPKGOMBCFTWEVKZDYBGVTMYDP")
        assertThat(schema.password).isEqualTo("yYGmRnUPBORxMoMcPptGvDYgKxmRSm")
    }
}
