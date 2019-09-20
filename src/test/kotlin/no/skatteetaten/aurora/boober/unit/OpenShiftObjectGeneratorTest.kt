package no.skatteetaten.aurora.boober.unit

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.size
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.mockk.every
import no.skatteetaten.aurora.boober.model.ApplicationDeploymentRef
import no.skatteetaten.aurora.boober.model.AuroraConfigFile
import no.skatteetaten.aurora.boober.model.openshift.ApplicationDeploymentCommand
import no.skatteetaten.aurora.boober.service.AuroraConfigRef
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecService
import no.skatteetaten.aurora.boober.service.OpenShiftObjectGenerator
import no.skatteetaten.aurora.boober.service.internal.ApplicationDeploymentGenerator
import no.skatteetaten.aurora.boober.service.internal.Provisions
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaInstance
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.ProvisioningResult
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultResults
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultSecretEnvResult
import no.skatteetaten.aurora.boober.utils.AbstractOpenShiftObjectGeneratorTest
import no.skatteetaten.aurora.boober.utils.addIfNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

class OpenShiftObjectGeneratorTest : AbstractOpenShiftObjectGeneratorTest() {

    lateinit var objectGenerator: OpenShiftObjectGenerator

    @BeforeEach
    fun setupTest() {
        objectGenerator = createObjectGenerator("hero")
    }

    @Test
    fun `ensure that message exist in application deployment object`() {

        val auroraConfigJson = defaultAuroraConfig()
        auroraConfigJson["utv/aos-simple.json"] = """{ "message": "Aurora <3" }"""

        val spec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        val auroraConfigRef = AuroraConfigRef("test", "master", "123")
        val command = ApplicationDeploymentCommand(mapOf(), DEFAULT_AID, auroraConfigRef)
        val provisions = Provisions(emptyList())
        val applicationDeployment = ApplicationDeploymentGenerator.generate(spec, "123", command, "luke", provisions)
        assertThat(applicationDeployment.spec.message).isEqualTo("Aurora <3")
    }

    @Test
    fun `ensure that database exist in application deployment object`() {

        val auroraConfigJson = defaultAuroraConfig()

        val spec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        val auroraConfigRef = AuroraConfigRef("test", "master", "123")
        val command = ApplicationDeploymentCommand(mapOf(), DEFAULT_AID, auroraConfigRef)
        val schema = DbhSchema(
            id = "123-456",
            type = "MANAGED",
            databaseInstance = DatabaseSchemaInstance(port = 1234, host = null),
            jdbcUrl = "",
            labels = mapOf("name" to "referanse"),
            users = emptyList()
        )
        val provisions = Provisions(listOf(schema))
        val applicationDeployment = ApplicationDeploymentGenerator.generate(spec, "123", command, "luke", provisions)

        assertThat(applicationDeployment.spec.databases).contains("123-456")
    }

    enum class OpenShiftObjectCreationTestData(
        val env: String,
        val appName: String,
        val templateFile: String? = null,
        val secretEnv: List<VaultSecretEnvResult> = listOf(),
        val secretName: String? = null,
        val overrides: List<AuroraConfigFile> = emptyList()
    ) {
        BOOBERDEV_SIMPLE(
            "booberdev", "aos-simple", overrides = listOf(
                AuroraConfigFile(
                    name = "booberdev/aos-simple.json",
                    contents = """{ "version": "1.0.4"}""",
                    override = true
                )
            )
        ),
        BOOBERDEV_TVINN("booberdev", "tvinn", templateFile = "atomhopper.json"),
        BOOBERDEV_REFERANSE("booberdev", "reference"),
        BOOBERDEV_CONSOLE("booberdev", "console"),
        WEBSEAL_SPROCKET("webseal", "sprocket"),
        BOOBERDEV_REFERANSE_WEB("booberdev", "reference-web"),
        SECRETTEST_SIMPLE(
            "secrettest", "aos-simple", secretEnv = listOf(
                VaultSecretEnvResult(
                    name = "aos-simple-foo",
                    secrets = mapOf("FOO" to "BAR".toByteArray(), "BAR" to "baz".toByteArray())
                )
            )
        ),
        RELEASE_SIMPLE("release", "aos-simple"),
        MOUNTS_SIMPLE("mounts", "aos-simple"),
        SECRETMOUNT_SIMPLE("secretmount", "aos-simple"),
    }

    @ParameterizedTest
    @EnumSource(OpenShiftObjectCreationTestData::class)
    fun `should create openshift objects`(test: OpenShiftObjectCreationTestData) {

        val provisioningResult = ProvisioningResult(
            vaultSecretEnvResult = test.secretEnv,
            vaultResults = VaultResults(mapOf("foo" to mapOf("latest.properties" to "FOO=bar\nBAR=baz\n".toByteArray())))
        )

        val aid = ApplicationDeploymentRef(test.env, test.appName)
        var additionalFile: String? = null
        if (test.templateFile != null) {

            additionalFile = "templates/${test.templateFile}"
            val templateFileName =
                "/samples/processedtemplate/${aid.environment}/${aid.application}/${test.templateFile}"
            val templateResult = this.javaClass.getResource(templateFileName)
            val jsonResult = mapper.readTree(templateResult)

            every { openShiftResourceClient.post(any(), any()) } returns ResponseEntity(jsonResult, HttpStatus.OK)
        }

        val auroraConfig = createAuroraConfig(aid, AFFILIATION, additionalFile)
        val deploymentSpec =
            AuroraDeploymentSpecService.createAuroraDeploymentSpecInternal(
                auroraConfig,
                aid,
                test.overrides
            )
        val ownerReference = OwnerReferenceBuilder()
            .withApiVersion("skatteetaten.no/v1")
            .withKind("ApplicationDeployment")
            .withName(deploymentSpec.name)
            .withUid("123-123")
            .build()

        val generatedObjects = listOf(objectGenerator.generateProjectRequest(deploymentSpec.environment))
            .addIfNotNull(
                objectGenerator.generateApplicationObjects(
                    DEPLOY_ID,
                    deploymentSpec,
                    provisioningResult,
                    ownerReference
                )
            )

        val resultFiles = getResultFiles(aid)

        val keys = resultFiles.keys

        generatedObjects.forEach {
            val key = getKey(it)
            assertThat(keys).contains(key)
            compareJson("/samples/result/${aid.environment}/${aid.application} $key", resultFiles[key]!!, it)
        }

        assertThat(generatedObjects.map { getKey(it) }.toSet()).isEqualTo(resultFiles.keys)
    }

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
    }

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

    private fun getArray(rolebinding: JsonNode, path: String): Set<String> {
        return (rolebinding.at(path) as ArrayNode).toSet().map { it.textValue() }.toSet()
    }

    // TODO: almost duplicate elsewhere
    fun compareJson(file: String, jsonNode: JsonNode, target: JsonNode): Boolean {
        val writer = mapper.writerWithDefaultPrettyPrinter()
        val targetString = writer.writeValueAsString(target)
        val nodeString = writer.writeValueAsString(jsonNode)
        val expected = "$file\n" + targetString
        val actual = "$file\n" + nodeString
        assertThat(expected).isEqualTo(actual)
        return true
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

}
