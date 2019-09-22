package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.size
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.feature.*
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.DatabaseInstance
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.*
import no.skatteetaten.aurora.boober.service.internal.ApplicationDeploymentGenerator
import no.skatteetaten.aurora.boober.service.internal.Provisions
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftResourceClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.*
import no.skatteetaten.aurora.boober.utils.AbstractOpenShiftObjectGeneratorTest
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.io.ByteArrayInputStream
import java.time.Duration
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

        val databaseInstance = DatabaseInstance(fallback = true, labels = mapOf("affiliation" to "aos"))
        val details = SchemaRequestDetails(
                schemaName = "openshift-console-api",
                users = listOf(SchemaUser("SCHEMA", "a", "aos")),
                engine = DatabaseEngine.ORACLE,
                affiliation = "aos",
                databaseInstance = databaseInstance
        )
        val request = SchemaForAppRequest(
                "booberdev",
                "openshift-console-api",
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
        every { databaseSchemaProvisioner.provisionSchemas(any()) } returns result
    }

    enum class ResourceCreationTestData(
            val appName: String,
            val env: String,
            val aditionalFile: String? = null
    ) {
        DEPLOY("booberdev", "console"),
        DEVELOPMENT("mounts", "aos-simple"),
        LOCAL_TEMPLATE("booberdev", "tvinn", "templates/atomhopper.json"),
        //TEMPLATE("booberdev", "oompa")
    }


    @ParameterizedTest
    @EnumSource(ResourceCreationTestData::class)
    fun `generate resources for deploy`(test: ResourceCreationTestData) {

        val aid = ApplicationDeploymentRef(test.appName, test.env)
        val auroraConfig = createAuroraConfig(aid, AFFILIATION, test.aditionalFile)
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
