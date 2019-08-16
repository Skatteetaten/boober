package no.skatteetaten.aurora.boober.unit

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSuccess
import assertk.assertions.message
import com.fasterxml.jackson.databind.JsonNode
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.controller.security.User
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidationException
import no.skatteetaten.aurora.boober.service.AuroraDeploymentSpecValidator
import no.skatteetaten.aurora.boober.service.OpenShiftTemplateProcessor
import no.skatteetaten.aurora.boober.service.ProvisioningException
import no.skatteetaten.aurora.boober.service.UserDetailsProvider
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftClient
import no.skatteetaten.aurora.boober.service.openshift.OpenShiftGroups
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaInstance
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DatabaseSchemaProvisioner
import no.skatteetaten.aurora.boober.service.resourceprovisioning.DbhSchema
import no.skatteetaten.aurora.boober.service.resourceprovisioning.StsProvisioner
import no.skatteetaten.aurora.boober.service.vault.VaultService
import no.skatteetaten.aurora.boober.utils.AbstractAuroraConfigTest
import no.skatteetaten.aurora.boober.utils.jsonMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

class AuroraDeploymentSpecValidatorTest : AbstractAuroraConfigTest() {

    lateinit var auroraConfigJson: MutableMap<String, String>

    val dbhSchema = DbhSchema(
        id = "123-123-123",
        type = "Oracle",
        databaseInstance = DatabaseSchemaInstance(8080, null),
        jdbcUrl = "jdbcurl"
    ) to "123-123-123"

    val udp = mockk<UserDetailsProvider>()
    val openShiftClient = mockk<OpenShiftClient>()
    val dbClient = mockk<DatabaseSchemaProvisioner>()
    val vaultService = mockk<VaultService>()
    val stsService = mockk<StsProvisioner>()

    val processor = OpenShiftTemplateProcessor(udp, mockk(), jsonMapper())
    val specValidator = AuroraDeploymentSpecValidator(
        openShiftClient, processor, Optional.of(dbClient),
        Optional.of(stsService), vaultService, "utv"
    )
    val mapper = jsonMapper()
    val atomhopperTemplate: JsonNode =
        mapper.readTree(this.javaClass.getResource("/samples/processedtemplate/booberdev/tvinn/atomhopper.json"))

    @BeforeEach
    fun setup() {
        auroraConfigJson = defaultAuroraConfig()
        clearAllMocks()
        every { udp.getAuthenticatedUser() } returns User("hero", "token", "Test User")
    }

    @Test
    fun `Fails when admin groups is empty`() {
        auroraConfigJson["utv/about.json"] = """{ "permissions": { "admin": "" }, "cluster" : "utv" }"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        assertThat {
            specValidator.assertIsValid(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
        }
    }

    @Test
    fun `Fails when admin groups contains no users`() {
        auroraConfigJson["utv/about.json"] = """{ "permissions": { "admin": "APP_PaaS_utv" }, "cluster" : "utv" }"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf("APP_PaaS_utv" to emptyList()))
        assertThat {
            specValidator.assertIsValid(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("All groups=[APP_PaaS_utv] are empty")
        }
    }

    @Test
    fun `Fails when admin groups does not exist`() {

        auroraConfigJson["utv/about.json"] = """{ "permissions": { "admin": "APP_PaaS_utv" }, "cluster" : "utv" }"""
        every { openShiftClient.getGroups() } returns OpenShiftGroups(emptyMap())
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat {
            specValidator.assertIsValid(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
        }
    }

    @Test
    fun `Fails when sts service not specified`() {

        auroraConfigJson["utv/aos-simple.json"] = """{ "certificate": true }"""
        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf("APP_PaaS_utv" to listOf("foo")))
        every { openShiftClient.getTemplate("atomhopper") } returns null

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        val validator = AuroraDeploymentSpecValidator(
            openShiftClient, processor, Optional.of(dbClient),
            Optional.empty(), vaultService, "utv"
        )

        assertThat {
            validator.assertIsValid(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("No sts service found in this cluster")
        }
    }

    @Test
    fun `Fails when webseal service not specified`() {

        auroraConfigJson["utv/aos-simple.json"] = """{ "webseal": true }"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        val validator = AuroraDeploymentSpecValidator(
            openShiftClient = openShiftClient,
            openShiftTemplateProcessor = processor,
            databaseSchemaProvisioner = Optional.of(dbClient),
            stsProvisioner = Optional.empty(),
            vaultService = vaultService,
            cluster = "utv"
        )

        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf("APP_PaaS_utv" to listOf("foo")))
        every { openShiftClient.getTemplate("atomhopper") } returns null

        assertThat {
            validator.assertIsValid(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("No webseal service found in this cluster")
        }
    }

    @Test
    fun `Fails when database service not specified`() {

        auroraConfigJson["utv/aos-simple.json"] = """{ "database": { "foo" : "123-123-123" } }"""

        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf("APP_PaaS_utv" to listOf("foo")))
        every { openShiftClient.getTemplate("atomhopper") } returns null
        every { dbClient.findSchemaById("123-123-123", any()) } returns dbhSchema

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        val validator = AuroraDeploymentSpecValidator(
            openShiftClient = openShiftClient,
            openShiftTemplateProcessor = processor,
            databaseSchemaProvisioner = Optional.empty(),
            stsProvisioner = Optional.of(stsService),
            vaultService = vaultService,
            cluster = "utv"
        )

        assertThat {
            validator.assertIsValid(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("No database service found in this cluster")
        }
    }

    @Test
    fun `Fails when databaseId does not exist`() {

        auroraConfigJson["utv/aos-simple.json"] = """{ "database": { "foo" : "123-123-123" } }"""

        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf("APP_PaaS_utv" to listOf("foo")))
        every { openShiftClient.getTemplate("atomhopper") } returns null
        every {
            dbClient.findSchemaById(
                "123-123-123",
                any()
            )
        } throws ProvisioningException("Expected dbh response to contain schema info.")

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat {
            specValidator.assertIsValid(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("Database schema with id=123-123-123 and affiliation=aos does not exist")
        }
    }

    @Test
    fun `Succeeds when databaseId does not exist when not on current cluster`() {

        auroraConfigJson["utv/aos-simple.json"] = """{ "cluster": "qa", "database": { "foo" : "123-123-123" } }"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        every { dbClient.findSchemaById("123-123-123", any()) } throws ProvisioningException(
            ""
        )

        assertThat { specValidator.validateDatabase(deploymentSpec) }.isSuccess()
    }

    @Test
    fun `Fails when template does not exist`() {

        auroraConfigJson["aos-simple.json"] =
            """{ "type": "template", "name": "aos-simple", "template": "atomhopper" }"""

        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf("APP_PaaS_utv" to listOf("foo")))
        every { openShiftClient.getTemplate("atomhopper") } returns null
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat {
            specValidator.assertIsValid(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
        }
    }

    @Test
    fun `Fails when parameters not in template`() {

        auroraConfigJson["aos-simple.json"] =
            """{ "type": "template", "name": "aos-simple", "template": "atomhopper", "parameters" : { "FOO" : "BAR"} }"""

        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf("APP_PaaS_utv" to listOf("foo")))
        every { openShiftClient.getTemplate("atomhopper") } returns atomhopperTemplate

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat {
            specValidator.assertIsValid(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("Required template parameters [FEED_NAME, DB_NAME, DOMAIN_NAME] not set. Template does not contain parameter(s) [FOO]")
        }
    }

    @Test
    fun `Fails when template does not contain required fields`() {

        auroraConfigJson["aos-simple.json"] =
            """{ "type": "template", "name": "aos-simple", "template": "atomhopper" }"""

        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf("APP_PaaS_utv" to listOf("foo")))
        every { openShiftClient.getTemplate("atomhopper") } returns atomhopperTemplate
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat {
            specValidator.assertIsValid(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("Required template parameters [FEED_NAME, DB_NAME, DOMAIN_NAME] not set")
        }
    }

    @Test
    fun `Fails when vault does not exist`() {

        auroraConfigJson["utv/aos-simple.json"] =
            """{ "secretVault": "test", "mounts": { "secret": { "type": "Secret", "secretVault": "test2", "path": "/tmp" } } }"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        val vaultCollection = deploymentSpec.environment.affiliation

        every { openShiftClient.getGroups() } returns OpenShiftGroups(mapOf("APP_PaaS_utv" to listOf("foo")))
        every { vaultService.vaultExists(vaultCollection, "test") } returns false
        every { vaultService.vaultExists(vaultCollection, "test2") } returns true

        assertThat {
            specValidator.assertIsValid(deploymentSpec)
        }.isFailure().all {
            hasMessage("File with name=latest.properties is not present in vault=test in collection=aos")
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
        }
    }

    @Test
    fun `Succeeds when vault exists`() {

        auroraConfigJson["utv/aos-simple.json"] =
            """{ "secretVault": "test", "mounts": { "secret": { "type": "Secret", "secretVault": "test2", "path": "/tmp" } } }"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)
        val vaultCollection = deploymentSpec.environment.affiliation

        every { vaultService.vaultExists(vaultCollection, "test2") } returns true
        every { vaultService.vaultExists(vaultCollection, "test") } returns true

        assertThat {
            specValidator.validateVaultExistence(deploymentSpec)
        }.isSuccess()
    }

    @Test
    fun `should handle overrides of secretVaultKeys with different syntax`() {

        auroraConfigJson["utv/about.json"] = """{ "secretVault": "foo", "cluster" : "utv" }"""
        auroraConfigJson["utv/aos-simple.json"] = """{ "secretVault": { "name":"foo2" }}"""

        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat(deploymentSpec.volume?.secrets?.first()?.secretVaultName).isEqualTo("foo2")
    }

    @Test
    fun `Fails when secretVaults keyMappings contains values not in keys`() {

        auroraConfigJson["utv/aos-simple.json"] =
            """{ "secretVaults": {"test" : { "keys":["test-key1"], "keyMappings": { "test-key2":"value" } }}}"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat {
            specValidator.validateKeyMappings(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("The secretVault keyMappings [test-key2] were not found in keys")
        }
    }
    @Test
    fun `Fails when secretVault keyMappings contains values not in keys`() {

        auroraConfigJson["utv/aos-simple.json"] =
            """{ "secretVault": { "name" : "test", "keys":["test-key1"], "keyMappings": { "test-key2":"value" } }}"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat {
            specValidator.validateKeyMappings(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("The secretVault keyMappings [test-key2] were not found in keys")
        }
    }

    @Test
    fun `Succeeds when secretVault contains keyMappings but no keys`() {

        auroraConfigJson["utv/aos-simple.json"] =
            """{ "secretVault": { "name": "test", "keyMappings": { "test-key2":"value" } }}"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat {
            specValidator.validateKeyMappings(deploymentSpec)
        }.isSuccess()
    }

    @Test
    fun `Fails when key in auroraConfig is not present in secretVault`() {

        auroraConfigJson["utv/aos-simple.json"] = """{ "secretVault": { "name": "test", "keys": ["test-key1"] }}"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        every { vaultService.findVaultKeys("aos", "test", "latest.properties") } returns setOf("test-key2")

        assertThat {
            specValidator.validateSecretVaultKeys(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("The keys [test-key1] were not found in the secret vault")
        }
    }

    @Test
    fun `Fails when file is not present in vault`() {

        auroraConfigJson["utv/aos-simple.json"] = """{ "secretVaults": { "name": { "file" : "foo.properties"}}}"""
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        every {
            vaultService.findFileInVault(
                "aos",
                "test",
                "foo.properties"
            )
        } throws IllegalArgumentException("Could not find vault file")

        assertThat {
            specValidator.validateSecretVaultFiles(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("File with name=foo.properties is not present in vault=name in collection=aos")
        }
    }

    @Test
    fun `Fails when the name of the the secretVault is to long`() {

        auroraConfigJson["utv/aos-simple.json"] = """{
            "secretVaults": {
              "this-is-way-more-then-63-characters-long-secretVault-name" : { }
              }
            }""".trimIndent()
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat {
            specValidator.validateSecretNames(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            message().isNotNull()
                .contains("secretVault=aos-simple-this-is-way-more-then-63-characters-long-secretvault-name is too long.")
        }
    }

    @Test
    fun `Fails when name of secrets is not unique`() {

        auroraConfigJson["utv/aos-simple.json"] = """{
            "secretVault": { "name": "test", "keys": ["test-key1"] },
            "secretVaults" : { "aos-simple" : {} }
            }""".trimIndent()
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat {
            specValidator.validateDuplicateSecretEnvNames(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("SecretVaults does not have unique names=[aos-simple, aos-simple]")
        }
    }

    @Test
    fun `Fails when existing mount not exist`() {

        auroraConfigJson["utv/aos-simple.json"] =
            """{ "secretVault": "test", "mounts": { "secret": { "type": "Secret", "secretVault": "test2", "exist" : true, "path": "/tmp" } } }"""

        every { openShiftClient.resourceExists(any(), any(), any()) } returns false
        val deploymentSpec = createDeploymentSpec(auroraConfigJson, DEFAULT_AID)

        assertThat {
            specValidator.validateExistingResources(deploymentSpec)
        }.isFailure().all {
            isInstanceOf(AuroraDeploymentSpecValidationException::class)
            hasMessage("Required existing resource with type=Secret namespace=aos-utv name=secret does not exist.")
        }
    }
}