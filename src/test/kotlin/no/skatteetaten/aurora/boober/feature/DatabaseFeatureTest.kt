package no.skatteetaten.aurora.boober.feature

import assertk.Assert
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import mu.KotlinLogging
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseEngine
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaInstance
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhUser
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaForAppRequest
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaProvisionResults
import no.skatteetaten.aurora.boober.service.resourceprovisioning.SchemaRequestDetails
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private val logger = KotlinLogging.logger { }

class DatabaseFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = DatabaseFeature(provisioner, userDetailsProvider, "utv")

    val userDetailsProvider: UserDetailsProvider = mockk()
    val provisioner: DatabaseSchemaProvisioner = mockk()

    @BeforeEach
    fun setupMock() {
        every { userDetailsProvider.getAuthenticatedUser() } returns User("username", "token")
    }

    @Test
    fun `create database secret`() {

        every { provisioner.provisionSchemas(any()) } returns createDatabaseResult("simple", "utv")

        val (adResource, dcResource, secretResource) = generateResources(
            """{ 
               "database" : true
           }""", resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        assertThat(dcResource).auroraDatabaseMounted(listOf(secretResource))
        assertThat(adResource).auroraDatabaseIdsAdded(listOf(secretResource))
    }

    @Test
    fun `should get error if schema with id does not exist`() {

        every {
            provisioner.provisionSchema(any())
        } throws IllegalArgumentException("Database schema with id=123456 and affiliation=paas does not exist")

        assertThat {
            createAuroraDeploymentContext(
                """{ 
               "database" : {
                  "simple" : "123456"
                }
           }"""
            )
        }.singleApplicationError("Database schema with id=123456 and affiliation=paas does not exist")
    }

    @Test
    fun `should get error if schema with generate false does not exist`() {

        every {
            provisioner.provisionSchema(any())
        } throws IllegalArgumentException("Could not find schema with labels")

        assertThat {
            createAuroraDeploymentContext(
                """{ 
               "database" : {
                 "foo" : {
                   "generate" : false
                  }
               }
           }"""
            )
        }.singleApplicationError("Could not find schema with labels")
    }

    @Test
    fun `create database secret with instance defaults`() {

        every { provisioner.provisionSchemas(any()) } returns createDatabaseResult("simple", "utv")

        // This is the validation query
        every { provisioner.provisionSchema(any()) } returns createDatabaseResult("simple", "utv").results.first()

        val (adResource, dcResource, secretResource) = generateResources(
            """{ 
               "database" : true,
               "databaseDefaults" : {
                  "tryReuse" : true,
                  "flavor" : "POSTGRES_MANAGED",
                  "generate" : false,
                  "instance" : {
                    "name" : "corrusant", 
                    "fallback" : true, 
                    "labels" : {
                       "type" : "ytelse"
                    }
                  }
                }
           }""", resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        assertThat(dcResource).auroraDatabaseMounted(listOf(secretResource))
        assertThat(adResource).auroraDatabaseIdsAdded(listOf(secretResource))
    }

    @Test
    fun `create database secret from id`() {

        every {
            provisioner.findSchema(any())
        } returns schema

        every { provisioner.provisionSchemas(any()) } returns createDatabaseResult("simple", "utv")

        // This is the validation query
        every { provisioner.provisionSchema(any()) } returns createDatabaseResult("simple", "utv").results.first()

        val (adResource, dcResource, secretResource) = generateResources(
            """{ 
               "database" : {
                 "simple" : "123456"
                }
           }""", resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        assertThat(dcResource).auroraDatabaseMounted(listOf(secretResource))
        assertThat(adResource).auroraDatabaseIdsAdded(listOf(secretResource))
    }

    @Test
    fun `ignore false database`() {

        val result = generateResources(
            """{ 
               "database" : false
           }"""
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `ignore false databases`() {

        val result = generateResources(
            """{ 
               "database" : {
                 "foo" : false,
                 "bar" : "false"
                }
           }"""
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `create two database secret with auto`() {

        every { provisioner.provisionSchemas(any()) } returns createDatabaseResult("foo,bar", "utv")

        val (adResource, dcResource, fooDatabase, barDatabase) = generateResources(
            """{ 
               "database" : {
                 "foo" : "auto",
                 "bar" : "auto"
                }
           }""",
            createdResources = 2,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        assertThat(dcResource).auroraDatabaseMounted(listOf(fooDatabase, barDatabase))
        assertThat(adResource).auroraDatabaseIdsAdded(listOf(fooDatabase, barDatabase))
    }

    @Test
    fun `create two database secret`() {

        every { provisioner.provisionSchemas(any()) } returns createDatabaseResult("foo,bar", "utv")

        val (adResource, dcResource, fooDatabase, barDatabase) = generateResources(
            """{ 
               "database" : {
                 "foo" : {
                   "enabled" : true,
                   "tryReuse" : true
                  },
                  "bar" : {
                   "enabled" : true
                  }
                }
           }""",
            createdResources = 2,
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        assertThat(dcResource).auroraDatabaseMounted(listOf(fooDatabase, barDatabase))
        assertThat(adResource).auroraDatabaseIdsAdded(listOf(fooDatabase, barDatabase))
    }

    @Test
    fun `create database ignore disabled`() {

        every { provisioner.provisionSchemas(any()) } returns createDatabaseResult("foo", "utv")

        val (adResource, dcResource, fooDatabase) = generateResources(
            """{ 
               "database" : {
                 "foo" : {
                   "enabled" : true
                  },
                  "bar" : {
                   "enabled" : false
                  }
                }
           }""",
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        assertThat(dcResource).auroraDatabaseMounted(listOf(fooDatabase))
        assertThat(adResource).auroraDatabaseIdsAdded(listOf(fooDatabase))
    }

    fun Assert<AuroraResource>.auroraDatabaseIdsAdded(
        databases: List<AuroraResource>
    ): Assert<AuroraResource> = transform { actual ->

        assertThat(actual).auroraResourceModifiedByThisFeatureWithComment("Added databaseId")

        val ad = actual.resource as ApplicationDeployment
        val expectedDbId = ad.spec.databases
        databases.forEach {
            val secret = it.resource as Secret
            assertThat(expectedDbId, "contains dbh id").contains(secret.metadata.labels["dbhId"])
        }
        actual
    }

    fun Assert<AuroraResource>.auroraDatabaseMounted(
        databases: List<AuroraResource>
    ): Assert<AuroraResource> = transform { actual ->

        assertThat(actual.resource).isInstanceOf(DeploymentConfig::class.java)
        assertThat(actual).auroraResourceModifiedByThisFeatureWithComment("Added env vars, volume mount, volume")

        val dc = actual.resource as DeploymentConfig
        val podSpec = dc.spec.template.spec
        val container = podSpec.containers[0]

        val firstEnv = databases.firstOrNull()?.let {
            val name = if ("${dc.metadata.name}-db" != it.resource.metadata.name) {
                it.resource.metadata.name.removePrefix("${dc.metadata.name}-")
            } else {
                it.resource.metadata.name
            }
            createDbEnv(name, "db")
        }

        val dbEnv = databases.flatMap {
            val name = if ("${dc.metadata.name}-db" != it.resource.metadata.name) {
                it.resource.metadata.name.removePrefix("${dc.metadata.name}-")
            } else {
                it.resource.metadata.name
            }
            createDbEnv(name)
        }.addIfNotNull(firstEnv).toMap()

        databases.forEachIndexed { index, it ->

            assertThat(it).auroraResourceCreatedByThisFeature()
            val secret = it.resource as Secret
            assertThat(secret.data.keys.toList())
                .isEqualTo(listOf("db.properties", "id", "info", "jdbcurl", "name"))

            val volume = podSpec.volumes[index]
            val volumeMount = container.volumeMounts[index]
            val volumeMountName = volumeMount.name
            assertThat(volume.name).isEqualTo(volumeMountName)
            assertThat(volume.secret.secretName).isEqualTo(it.resource.metadata.name)
        }

        val env: Map<String, String> = container.env.associate { it.name to it.value }
        assertThat(dbEnv, "Env vars").isEqualTo(env)
        actual
    }
}

val schema = DbhSchema(
    id = "123",
    type = "SCHEMA",
    databaseInstance = DatabaseSchemaInstance(1512, "localhost"),
    jdbcUrl = "foo/bar/baz",
    labels = emptyMap(),
    users = listOf(DbhUser("username", "password", type = "SCHEMA"))
)

fun createDatabaseResult(databaseNames: String, env: String): SchemaProvisionResults {

    val databases = databaseNames.split((",")).map { appName ->
        createSchemaProvisionResult(env, appName)
    }
    return SchemaProvisionResults(databases)
}

fun createSchemaProvisionResult(
    env: String,
    appName: String
): SchemaProvisionResult {
    val databaseInstance = DatabaseInstance(fallback = true, labels = mapOf("affiliation" to "aos"))
    return SchemaProvisionResult(
        request = SchemaForAppRequest(
            environment = env,
            application = appName,
            generate = true,
            user = User("username", "token"),
            details = SchemaRequestDetails(
                schemaName = appName,
                engine = DatabaseEngine.ORACLE,
                affiliation = "aos",
                databaseInstance = databaseInstance
            ),
            tryReuse = false
        ),
        dbhSchema = schema
    )
}
