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
import no.skatteetaten.aurora.boober.mapper.createAuroraDeploymentCommand
import no.skatteetaten.aurora.boober.mapper.createResources
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.service.*
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.*
import no.skatteetaten.aurora.boober.utils.AbstractOpenShiftObjectGeneratorTest
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayInputStream
import java.time.Instant

class OpenShiftObjectResourceGeneratorTest : AbstractOpenShiftObjectGeneratorTest() {

    lateinit var service: AuroraDeploymentSpecService

    val cluster = "utv"
    val openShiftClient: OpenShiftClient = mockk()
    val vaultProvider: VaultProvider = mockk()
    val stsProvisioner: StsProvisioner = mockk()
    val databaseSchemaProvisioner: DatabaseSchemaProvisioner = mockk()
    val features: List<Feature> = listOf(
            JavaDeployFeature("docker-registry.aurora.sits.no:5000"),
            WebDeployFeature("docker-registry.aurora.sits.no:5000"),
            CommonLabelFeature(userDetailsProvider),
            DeploymentConfigFeature(),
            RouteFeature(".utv.paas.skead.no"),
            LocalTemplateFeature(),
            TemplateFeature(openShiftClient),
            BuildFeature(),
            DatabaseFeature(databaseSchemaProvisioner, cluster),
            WebsealFeature(),
            ConfigFeature(vaultProvider, cluster),
            StsFeature(stsProvisioner),
            MountFeature(vaultProvider, cluster, openShiftClient),
            ApplicationDeploymentFeature()
    )

    @BeforeEach
    fun setupTest() {
        service = AuroraDeploymentSpecService(
                featuers = features,
                validationPoolSize = 1
        )
        val template: String = this.javaClass.getResource("/samples/config/templates/atomhopper.json").readText()
        every { userDetailsProvider.getAuthenticatedUser() } returns User("hero", "token", "Aurora OpenShift")
        every { openShiftClient.getTemplate("atomhopper") } returns (jacksonObjectMapper().readTree(template))

        every { vaultProvider.findVaultDataSingle(any()) } returns
                mapOf("latest.properties" to "FOO=bar\nBAR=baz\n".toByteArray())

        every { vaultProvider.findVaultData(any()) } returns
                VaultResults(mapOf("foo" to mapOf("latest.properties" to "FOO=bar\nBAR=baz\n".toByteArray())))

        val cert = StsProvisioner.createStsCert(ByteArrayInputStream(loadByteResource("keystore.jks")), "ca", "")
        val stsResult = StsProvisioningResult("commonName", cert, Instant.EPOCH)
        every { stsProvisioner.generateCertificate(any(), any(), any()) } returns stsResult

    }

    private fun createDatabaseResult(databaseNames: String, env: String): SchemaProvisionResults {
        val databaseInstance = DatabaseInstance(fallback = true, labels = mapOf("affiliation" to "aos"))
        val databases = databaseNames.split((",")).map { appName ->
            SchemaProvisionResult(
                    request = SchemaForAppRequest(
                            environment = env,
                            application = appName,
                            generate = true,
                            details = SchemaRequestDetails(
                                    schemaName = appName,
                                    users = listOf(SchemaUser("SCHEMA", "a", "aos")),
                                    engine = DatabaseEngine.ORACLE,
                                    affiliation = "aos",
                                    databaseInstance = databaseInstance
                            )
                    ),
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
        }
        return SchemaProvisionResults(databases)
    }

    enum class ResourceCreationTestData(
            val env: String,
            val appName: String,
            val dbName: String = appName,
            val additionalFile: String? = null,
            val overrides: List<AuroraConfigFile> = emptyList()
    ) {
        BOOBERDEV_CONSOLE("booberdev", "console", "openshift-console-api"),
        MOUNTS_SIMPLE("mounts", "aos-simple"),
        BOOBERDEV_TVINN("booberdev", "tvinn", additionalFile = "templates/atomhopper.json"),
        BOOBERDEV_SIMPLE(
                "booberdev", "aos-simple", overrides = listOf(
                AuroraConfigFile(
                        name = "booberdev/aos-simple.json",
                        contents = """{ "version": "1.0.4"}""",
                        override = true
                ))
        ),
        BOOBERDEV_REFERANSE("booberdev", "reference"),
        WEBSEAL_SPROCKET("webseal", "sprocket", "sprocket,reference"),
        BOOBERDEV_REFERANSE_WEB("booberdev", "reference-web"),
        SECRETTEST_SIMPLE("secrettest", "aos-simple"),
        RELEASE_SIMPLE("release", "aos-simple"),
        SECRETMOUNT_SIMPLE("secretmount", "aos-simple"),
    }


    @ParameterizedTest
    @EnumSource(ResourceCreationTestData::class)
    fun `generate resources for deploy`(test: ResourceCreationTestData) {
        Instants.determineNow = { Instant.EPOCH }
        // TODO: toxiproxy, advanced db, see coverage, TTL, validation
        val aid = ApplicationDeploymentRef(test.env, test.appName)
        val auroraConfig = createAuroraConfig(aid, AFFILIATION, test.additionalFile)
        every { databaseSchemaProvisioner.provisionSchemas(any()) } returns createDatabaseResult(test.dbName, test.env)

        val deployCommand = createAuroraDeploymentCommand(
                auroraConfig = auroraConfig, applicationDeploymentRef = aid, overrideFiles = test.overrides, deployId = "123", auroraConfigRef = AuroraConfigRef("test", "master", "123abb")
        )
        val ctx = service.createAuroraDeploymentContext(deployCommand)
        val resources = ctx.createResources()
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

//TODO: reimplement
/*
@Test
fun `generate rolebinding should include serviceaccount `() {

    val aid = ApplicationDeploymentRef("booberdev", "console")
    val auroraConfig = createAuroraConfig(aid, AFFILIATION, null)

    val deploymentSpec =
        AuroraDeploymentSpecService.createAuroraDeploymentSpecInternal(
            auroraConfig,
            aid
        )
    val rolebindings = objectGenerator.generateRolebindings(
        deploymentSpec.environment.permissions,
        deploymentSpec.environment.namespace
    )

    val adminRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "admin" }!!
    assertThat(adminRolebinding).isNotNull()

    assertThat(
        getArray(
            adminRolebinding,
            "/userNames"
        )
    ).isEqualTo(setOf("system:serviceaccount:paas:jenkinsbuilder"))
    assertThat(getArray(adminRolebinding, "/groupNames")).isEqualTo(setOf("APP_PaaS_utv", "APP_PaaS_drift"))

    val viewRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "view" }
    assertThat(viewRolebinding).isNotNull()
    assertThat(rolebindings.size).isEqualTo(2)
}    */

/* TOOD: Reimplement
@Test
fun `generate rolebinding view should split groups`() {

    val aid = ApplicationDeploymentRef("booberdev", "console")
    val auroraConfig = createAuroraConfig(aid, AFFILIATION, null)

    val deploymentSpec =
        AuroraDeploymentSpecService.createAuroraDeploymentSpecInternal(
            auroraConfig,
            aid
        )
    val rolebindings = objectGenerator.generateRolebindings(
        deploymentSpec.environment.permissions,
        deploymentSpec.environment.namespace
    )

    val adminRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "admin" }!!
    assertThat(getArray(adminRolebinding, "/groupNames")).isEqualTo(setOf("APP_PaaS_utv", "APP_PaaS_drift"))

    val viewRolebinding = rolebindings.find { it.at("/metadata/name").asText() == "view" }
    assertThat(viewRolebinding).isNotNull()
    assertThat(rolebindings.size).isEqualTo(2)
}

 */