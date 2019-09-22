package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.feature.*
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.DatabaseInstance
import no.skatteetaten.aurora.boober.service.*
import no.skatteetaten.aurora.boober.service.resourceprovisioning.*
import no.skatteetaten.aurora.boober.utils.AbstractOpenShiftObjectGeneratorTest
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.ResponseEntity
import java.io.ByteArrayInputStream
import java.time.Instant

class OpenShiftObjectResourceGeneratorTest : AbstractOpenShiftObjectGeneratorTest() {

    lateinit var service: AuroraDeploymentSpecService

    val stsProvisioner: StsProvisioner = mockk()
    val databaseSchemaProvisioner: DatabaseSchemaProvisioner = mockk()
    val features: List<Feature> = listOf(
            DeployFeature("docker-registry.aurora.sits.no:5000"),
            CommonLabelFeature(userDetailsProvider),
            DeploymentConfigFeature(),
            RouteFeature(".utv.paas.skead.no"),
            LocalTemplateFeature(),
            TemplateFeature(openShiftResourceClient),
            BuildFeature(),
            DatabaseFeature(databaseSchemaProvisioner),
            WebsealFeature(),
            ConfigFeature(),
            StsFeature(stsProvisioner)
    )

    @BeforeEach
    fun setupTest() {
        service = AuroraDeploymentSpecService(
                auroraConfigService = mockk(),
                aphBeans = emptyList(),
                featuers = features
        )
        val template: String = this.javaClass.getResource("/samples/config/templates/atomhopper.json").readText()
        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token", "Aurora OpenShift")
        every { openShiftResourceClient.get("template", "openshift", "atomhopper", true) } returns
                ResponseEntity.ok(jacksonObjectMapper().readTree(template))

        val cert = StsProvisioner.createStsCert(ByteArrayInputStream(loadByteResource("keystore.jks")), "ca", "")
        val stsResult = StsProvisioningResult("commonName", cert, Instant.EPOCH)
        every { stsProvisioner.generateCertificate(any(), any(), any()) } returns stsResult


    }

    private fun createDatabaseResult(appName: String, env: String): SchemaProvisionResults {
        val databaseInstance = DatabaseInstance(fallback = true, labels = mapOf("affiliation" to "aos"))
        val details = SchemaRequestDetails(
                schemaName = appName,
                users = listOf(SchemaUser("SCHEMA", "a", "aos")),
                engine = DatabaseEngine.ORACLE,
                affiliation = "aos",
                databaseInstance = databaseInstance
        )
        val request = SchemaForAppRequest(
                env,
                appName,
                true,
                details
        )

        val result = SchemaProvisionResults(
                listOf(
                        SchemaProvisionResult(
                                request = request,
                                dbhSchema = DbhSchema(
                                        id = "123",
                                        type = "SCHEMA",
                                        databaseInstance = DatabaseSchemaInstance(1512, "localhost"),
                                        jdbcUrl = "foo/bar/baz",
                                        labels = emptyMap(),
                                        users = listOf(DbhUser("username", "password", type = "SCHEMA"))
                                ),
                                responseText = "OK"
                        )
                )
        )
        return result
    }

    enum class ResourceCreationTestData(
            val env: String,
            val appName: String,
            val dbName: String = appName,
            val aditionalFile: String? = null
    ) {
        DEPLOY("booberdev", "console", "openshift-console-api"),
        DEVELOPMENT("mounts", "aos-simple"),
        LOCAL_TEMPLATE("booberdev", "tvinn", aditionalFile = "templates/atomhopper.json"),
        //TEMPLATE("booberdev", "oompa")
    }


    @ParameterizedTest
    @EnumSource(ResourceCreationTestData::class)
    fun `generate resources for deploy`(test: ResourceCreationTestData) {

        val aid = ApplicationDeploymentRef(test.env, test.appName)
        val auroraConfig = createAuroraConfig(aid, AFFILIATION, test.aditionalFile)
        every { databaseSchemaProvisioner.provisionSchemas(any()) } returns createDatabaseResult(test.dbName, test.env)

        val resources = service.createResources(auroraConfig, aid, deployId = "123")
        val resultFiles = getResultFiles(aid)
        val keys = resultFiles.keys
        val generatedObjects = resources.map {
            val json: JsonNode = jacksonObjectMapper().convertValue(it.resource)
            json
        }
        generatedObjects.forEach {
            val key = getKey(it)
            assertThat(keys).contains(key)
            if (it.openshiftKind == "secret") {
                val data = it["data"] as ObjectNode
                data.fields().forEach { (key, _) ->
                    data.put(key, "REMOVED_IN_TEST")
                }
            }
            compareJson(resultFiles[key]!!, it)
        }
        assertThat(generatedObjects.map { getKey(it) }.toSet()).isEqualTo(resultFiles.keys)
    }
}
