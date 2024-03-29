package no.skatteetaten.aurora.boober.feature

import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import assertk.Assert
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSuccess
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.facade.json
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeployment
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaInstance
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbApiEnvelope
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhRestTemplateWrapper
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhUser
import no.skatteetaten.aurora.boober.service.resourceprovisioning.RestorableSchema
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.singleApplicationError
import no.skatteetaten.aurora.boober.utils.singleApplicationErrorResult
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.HttpMock
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.httpMockServer
import okhttp3.mockwebserver.MockResponse

class DatabaseFeatureTest : AbstractFeatureTest() {
    val provisioner = DatabaseSchemaProvisioner(
        DbhRestTemplateWrapper(RestTemplateBuilder().build(), "http://localhost:5000", 0),
        jacksonObjectMapper()
    )
    override val feature: Feature
        get() = DatabaseFeature(provisioner, userDetailsProvider, "utv")

    val userDetailsProvider: UserDetailsProvider = mockk()

    @BeforeEach
    fun setupMock() {
        every { userDetailsProvider.getAuthenticatedUser() } returns User("username", "token")
    }

    @AfterEach
    fun clearMocks() {
        HttpMock.clearAllHttpMocks()
    }

    @Test
    fun `create database secret`() {
        httpMockServer(5000) {
            rule {
                json(DbApiEnvelope("ok", listOf(schema)))
            }
        }

        val (adResource, dcResource, secretResource) = generateResources(
            """{ 
               "database" : true
           }""",
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        assertThat(dcResource).auroraDatabaseMounted(listOf(secretResource))
        assertThat(adResource).auroraDatabaseIdsAdded(listOf(secretResource))
    }

    @Test
    fun `should get error if schema with id does not exist`() {
        httpMockServer(5000) {
            rule {
                json(DbApiEnvelope<DbhSchema>("ok", emptyList()))
            }
        }

        assertThat {
            createAuroraDeploymentContext(
                """{ 
               "database" : {
                  "simple" : "123456"
                }
           }"""
            )
        }.singleApplicationErrorResult("Could not find schema")
    }

    @Test
    fun `should get error if schema with generate false does not exist`() {
        httpMockServer(5000) {
            rule {
                json(DbApiEnvelope<DbhSchema>("ok", emptyList()))
            }
        }
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
        }.singleApplicationErrorResult("Could not find schema")
    }

    @Test
    fun `create database secret with instance defaults`() {

        httpMockServer(5000) {
            rule {
                json(DbApiEnvelope("ok", listOf(schema)))
            }
        }

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
           }""",
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        assertThat(dcResource).auroraDatabaseMounted(listOf(secretResource))
        assertThat(adResource).auroraDatabaseIdsAdded(listOf(secretResource))
    }

    @Test
    fun `create database secret from id`() {
        httpMockServer(5000) {
            rule {
                json(DbApiEnvelope("ok", listOf(schema)))
            }
        }

        val (adResource, dcResource, secretResource) = generateResources(
            """{ 
               "database" : {
                 "simple" : "123456"
                }
           }""",
            resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig())
        )

        assertThat(dcResource).auroraDatabaseMounted(listOf(secretResource))
        assertThat(adResource).auroraDatabaseIdsAdded(listOf(secretResource))
    }

    @Test
    fun `should get error if schema with id is not in the selected affiliation`() {
        val uuid = UUID.randomUUID()

        httpMockServer(5000) {
            rule {
                json(DbApiEnvelope("ok", listOf(createDbhSchema(uuid))))
            }
        }

        assertThat {
            generateResources(
                """{ 
               "database" : {
                 "simple" : "$uuid"
                }
           }""",
                resources = mutableSetOf(createEmptyApplicationDeployment(), createEmptyDeploymentConfig()),
                files = listOf(
                    AuroraConfigFile(
                        "about.json",
                        """{
                         "schemaVersion": "v1",
                         "type": "deploy",
                         "cluster": "utv",
                         "affiliation": "aurora"
                    }"""
                    )
                )
            )
        }.singleApplicationError("Schema with id=$uuid is located in the affiliation=paas, current affiliation=aurora. Using schema with id across affiliations is not allowed")
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
    fun `Should not run full validation when cluster is not same as boober cluster`() {
        assertThat {
            createAuroraDeploymentContext(
                """ {
                    "databaseDefaults": {
                        "generate": false
                    },
                    "cluster": "notsamecluster",
                   "database": true
                   } 
                """.trimIndent()
            )
        }.isSuccess()
    }

    @Test
    fun `create two database secret with auto`() {
        httpMockServer(5000) {
            rule {
                json(DbApiEnvelope("ok", listOf(schema)))
            }
        }

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

        httpMockServer(5000) {
            rule {
                json(DbApiEnvelope("ok", listOf(schema)))
            }
        }

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
    fun `get two dbs with tryReuse as default and generate disabled`() {
        val barSchema = createRestorableSchema()
        val fooSchema = createRestorableSchema()

        httpMockServer(5000) {
            rule({
                path!!.contains("/schema/") && method == "GET"
            }) {
                json(DbApiEnvelope("ok", emptyList<Any>()))
            }

            rule({
                path!!.contains("/restorableSchema/") && method == "GET" && path!!.contains(barSchema.databaseSchema.id)
            }) {
                json(DbApiEnvelope("ok", listOf(barSchema)))
            }

            rule({
                path!!.contains("/restorableSchema/") && method == "GET"
            }) {
                json(DbApiEnvelope("ok", listOf(fooSchema)))
            }
        }

        assertThat {
            createAuroraDeploymentContext(
                """{ 
               "database" : {
                 "foo": {
                    "enabled": true
                 },
                 "bar": {
                    "id": "${barSchema.databaseSchema.id}"
                 }
               },
               "databaseDefaults" : {
                  "tryReuse" : true,
                  "flavor" : "POSTGRES_MANAGED",
                  "generate" : false
                }
           }"""
            )
        }.isSuccess()
    }

    @Test
    fun `create database ignore disabled`() {

        httpMockServer(5000) {
            rule {
                json(DbApiEnvelope("ok", listOf(schema)))
            }
        }

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

    @Test
    fun `when ignoreMissingSchema is true do not fail validation or generate resource when generate is false and schema is not found`() {
        httpMockServer(5000) {
            rule({ method == "GET" }) {
                MockResponse()
                    .setResponseCode(404)
            }
        }

        val res = generateResources(
            """{
              "databaseDefaults": {
                "generate": false,
                "tryReuse": true,
                "ignoreMissingSchema": true
              },
              "database": {
                "foo": {
                  "enabled": true
                },
                "bar": {
                  "enabled": true
                }
              }
            }"""
        )

        assertThat(res).isEmpty()
    }

    @Test
    fun `when ignoreMissingSchema is true and generate is false then it should generate resource if schema is found`() {
        httpMockServer(5000) {
            rule({ method == "GET" && path!!.contains("foo") }) {
                json(DbApiEnvelope("ok", listOf(schema)))
            }
            rule({ method == "GET" }) {
                MockResponse()
                    .setResponseCode(404)
            }
        }

        val res = generateResources(
            """{
              "databaseDefaults": {
                "generate": false,
                "tryReuse": true,
                "ignoreMissingSchema": true
              },
              "database": {
                "foo": {
                  "enabled": true
                },
                "bar": {
                  "enabled": true
                }
              }
            }"""
        )

        assertThat(res).hasSize(1)
    }

    @Test
    fun `fail when generate is false and schema is missing when overriding ignoreMissingSchema`() {
        httpMockServer(5000) {
            rule({ method == "GET" }) {
                MockResponse()
                    .setResponseCode(404)
            }
        }

        assertThat {
            generateResources(
                """{
              "databaseDefaults": {
                "generate": false,
                "ignoreMissingSchema": true
              },
              "database": {
                "foo": {
                  "enabled": true
                },
                "bar": {
                  "enabled": true,
                  "ignoreMissingSchema": false
                }
              }
            }"""
            )
        }.singleApplicationError("Could not find schema with name=bar")
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

fun createRestorableSchema(id: UUID = UUID.randomUUID()) =
    RestorableSchema(setToCooldownAt = 0L, deleteAfter = 0L, databaseSchema = createDbhSchema(id))

fun createDbhSchema(
    id: UUID = UUID.randomUUID(),
    jdbcUrl: String = "foo/bar/baz",
    databaseInstance: DatabaseSchemaInstance = DatabaseSchemaInstance(1512, "localhost")
) =
    DbhSchema(
        id = id.toString(),
        type = "SCHEMA",
        databaseInstance = databaseInstance,
        jdbcUrl = jdbcUrl,
        labels = mapOf(
            "affiliation" to "paas",
            "name" to "myApp"
        ),
        users = listOf(DbhUser("username", "password", type = "SCHEMA"))
    )

val schema = createDbhSchema()
