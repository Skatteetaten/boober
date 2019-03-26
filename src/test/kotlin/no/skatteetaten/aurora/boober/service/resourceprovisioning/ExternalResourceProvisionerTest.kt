package no.skatteetaten.aurora.boober.service.resourceprovisioning

import assertk.assertThat
import assertk.assertions.isEqualTo
import no.skatteetaten.aurora.boober.model.AbstractAuroraConfigTest2
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef.Companion.aid
import no.skatteetaten.aurora.boober.model.AuroraDeploymentSpecInternal
import no.skatteetaten.aurora.boober.model.DatabaseInstance
import org.junit.jupiter.api.Test

class ExternalResourceProvisionerTest : AbstractAuroraConfigTest2() {

    val databaseInstance = DatabaseInstance(fallback = true, labels = mapOf("affiliation" to "aos"))
    val details = SchemaRequestDetails(
        schemaName = "reference", users = listOf(SchemaUser("SCHEMA", "a", "aos")),
        engine = DatabaseEngine.ORACLE,
        affiliation = "aos",
        databaseInstance = databaseInstance
    )

    @Test
    fun `Auto provisioned named schema`() {

        val spec = createDeploySpecWithDbSpec("""{ "database" : { "REFerence" : "auto" } }""")
        val requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

        assertThat(requests.size).isEqualTo(1)
        assertThat(requests.first()).isEqualTo(SchemaForAppRequest("utv", "reference", true, details))
    }

    @Test
    fun `Auto provisioned schema with val ault name`() {

        val spec = createDeploySpecWithDbSpec("""{ "database": true }""")

        val requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)

        assertThat(requests.size).isEqualTo(1)
        assertThat(requests.first()).isEqualTo(SchemaForAppRequest("utv", "reference", true, details))
    }

    @Test
    fun `Named schema with explicit id`() {

        val spec =
            createDeploySpecWithDbSpec("""{ "database": { "REFerence": "fd59dba9-7d67-4ea2-bb98-081a5df8c387" } }""")

        val requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)


        assertThat(requests.size).isEqualTo(1)
        assertThat(requests.first()).isEqualTo(SchemaIdRequest("fd59dba9-7d67-4ea2-bb98-081a5df8c387", details))
    }

    @Test
    fun `Multiple schemas`() {
        val dbSpec = """{
        "database": {
        "REFerence": "fd59dba9-7d67-4ea2-bb98-081a5df8c387",
        "backup": "auto"
    }
    }"""

        val spec = createDeploySpecWithDbSpec(dbSpec)

        val requests = ExternalResourceProvisioner.createSchemaProvisionRequestsFromDeploymentSpec(spec)


        assertThat(requests.size).isEqualTo(2)
    }

    fun createDeploySpecWithDbSpec(dbSpec: String): AuroraDeploymentSpecInternal {
        val config = defaultAuroraConfig()
        config["reference.json"] = REF_APP_JSON
        config["utv/reference.json"] = dbSpec

        return createDeploymentSpec(config, aid("utv", "reference"))
    }
}