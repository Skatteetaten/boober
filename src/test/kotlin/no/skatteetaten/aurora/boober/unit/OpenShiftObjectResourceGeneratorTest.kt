package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.feature.ApplicationDeploymentFeature
import no.skatteetaten.aurora.boober.feature.BuildFeature
import no.skatteetaten.aurora.boober.feature.CommonLabelFeature
import no.skatteetaten.aurora.boober.feature.ConfigFeature
import no.skatteetaten.aurora.boober.feature.DatabaseFeature
import no.skatteetaten.aurora.boober.feature.DeploymentConfigFeature
import no.skatteetaten.aurora.boober.feature.EnvironmentFeature
import no.skatteetaten.aurora.boober.feature.Feature
import no.skatteetaten.aurora.boober.feature.JavaDeployFeature
import no.skatteetaten.aurora.boober.feature.LocalTemplateFeature
import no.skatteetaten.aurora.boober.feature.MountFeature
import no.skatteetaten.aurora.boober.feature.RouteFeature
import no.skatteetaten.aurora.boober.feature.SecretVaultFeature
import no.skatteetaten.aurora.boober.feature.StsFeature
import no.skatteetaten.aurora.boober.feature.TemplateFeature
import no.skatteetaten.aurora.boober.feature.ToxiproxySidecarFeature
import no.skatteetaten.aurora.boober.feature.WebDeployFeature
import no.skatteetaten.aurora.boober.feature.WebsealFeature
import no.skatteetaten.aurora.boober.feature.createDatabaseResult
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.AuroraContextCommand
import no.skatteetaten.aurora.boober.model.createResources
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeploymentContextService
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioningResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultResults
import no.skatteetaten.aurora.boober.utils.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.utils.Instants
import no.skatteetaten.aurora.boober.utils.openshiftKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayInputStream
import java.time.Instant

// TODO: create one or two "fat" tests here and remove the rest
class OpenShiftObjectResourceGeneratorTest : AbstractAuroraConfigTest() {

    val userDetailsProvider = mockk<UserDetailsProvider>()

    @BeforeEach
    fun setup() {
        clearAllMocks()
    }

    fun getKey(it: JsonNode): String {
        val kind = it.get("kind").asText().toLowerCase()
        val metadata = it.get("metadata")

        val name = if (metadata == null) {
            it.get("name").asText().toLowerCase()
        } else {
            metadata.get("name").asText().toLowerCase()
        }

        return "$kind/$name"
    }

    lateinit var service: AuroraDeploymentContextService

    val cluster = "utv"
    val openShiftClient: OpenShiftClient = mockk()
    val vaultProvider: VaultProvider = mockk()
    val stsProvisioner: StsProvisioner = mockk()
    val databaseSchemaProvisioner: DatabaseSchemaProvisioner = mockk()
    val features: List<Feature> = listOf(
        ToxiproxySidecarFeature(),
        JavaDeployFeature("docker.registry:5000"),
        WebDeployFeature("docker.registry:5000"),
        CommonLabelFeature(userDetailsProvider),
        DeploymentConfigFeature(),
        RouteFeature(".test.paas"),
        LocalTemplateFeature(),
        TemplateFeature(openShiftClient),
        BuildFeature(),
        DatabaseFeature(databaseSchemaProvisioner, "utv"),
        WebsealFeature(),
        SecretVaultFeature(vaultProvider),
        ConfigFeature(),
        StsFeature(stsProvisioner),
        MountFeature(vaultProvider, cluster, openShiftClient),
        ApplicationDeploymentFeature(),
        EnvironmentFeature(openShiftClient, userDetailsProvider)
    )

    @BeforeEach
    fun setupTest() {
        service = AuroraDeploymentContextService(featuers = features)
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

    enum class ResourceCreationTestData(
        val env: String,
        val appName: String,
        val dbName: String = appName,
        val additionalFile: String? = null,
        val overrides: List<AuroraConfigFile> = emptyList()
    ) {
        SIMPLE_UTV("utv", "simple"),
        EASY_UTV("utv", "easy", additionalFile = "simple.json"),
        COMPLEX_UTV(
            "utv", "complex", dbName = "foo,complex", overrides = listOf(
                AuroraConfigFile(
                    name = "utv/complex.json",
                    contents = """{ "version": "1.0.4"}""",
                    override = true
                )
            ), additionalFile = "utv/about-alternate.json"
        ),
        AH_UTV("utv", "ah", additionalFile = "templates/atomhopper.json"),
        WEB("utv", "web")
    }

    @ParameterizedTest
    @EnumSource(ResourceCreationTestData::class)
    fun `generate resources for deploy`(test: ResourceCreationTestData) {
        Instants.determineNow = { Instant.EPOCH }
        val aid = ApplicationDeploymentRef(test.env, test.appName)
        val auroraConfig = createAuroraConfig(aid, AFFILIATION, test.additionalFile)
        every { databaseSchemaProvisioner.provisionSchemas(any()) } returns createDatabaseResult(test.dbName, test.env)

        val deployCommand = AuroraContextCommand(
            auroraConfig = auroraConfig,
            applicationDeploymentRef = aid,
            overrides = test.overrides,
            auroraConfigRef = AuroraConfigRef("test", "master", "123abb")
        )
        //burde denne validere slik feature testene gjør?
        val ctx = service.createAuroraDeploymentContext(deployCommand)
        val resourceResult = ctx.createResources()

        val resources = resourceResult.second!!
        val resultFiles = getResultFiles(aid)
        val keys = resultFiles.keys
        val generatedObjects = resources.map {
            val json: JsonNode = jacksonObjectMapper().convertValue(it.resource)
            json
        }
        generatedObjects.forEach {
            val key: String = getKey(it)
            assertThat(keys).contains(key)
            if (it.openshiftKind == "secret") {
                val data = it["data"] as ObjectNode
                data.fields().forEach { (key, _) ->
                    data.put(key, "REMOVED_IN_TEST")
                }
            }
            compareJson(resultFiles[key]!!, it)
        }
        assertThat(generatedObjects.map { getKey(it) }.toSortedSet()).isEqualTo(resultFiles.keys.toSortedSet())
    }
}
