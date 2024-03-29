package no.skatteetaten.aurora.boober.feature

import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.openshift.api.model.DeploymentConfig
import io.mockk.every
import io.mockk.mockk
import no.skatteetaten.aurora.boober.model.AuroraResource
import no.skatteetaten.aurora.boober.service.UnauthorizedAccessException
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultProvider
import no.skatteetaten.aurora.boober.service.resourceprovisioning.VaultRequest
import no.skatteetaten.aurora.boober.utils.AbstractFeatureTest
import no.skatteetaten.aurora.boober.utils.applicationErrorResult
import no.skatteetaten.aurora.boober.utils.singleApplicationErrorResult

class SecretVaultFeatureTest : AbstractFeatureTest() {
    override val feature: Feature
        get() = SecretVaultFeature(vaultProvider)

    private val vaultProvider: VaultProvider = mockk()

    @Test
    fun `validate secretNames`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        val tooLongName = "this-secret-name-is-really-way-way-way-too-long-long-long-long"
        mockVault(tooLongName)
        assertThat {
            createAuroraDeploymentContext(
                """{
              "secretVaults" : {
                  "$tooLongName" : {
                    "enabled" : true
                  }
              }
             }"""
            )
        }.singleApplicationErrorResult("The name of the secretVault=simple-this-secret-name-is-really-way-way-way-too-long-long-long-long is too long. Max 63 characters. Note that we ensure that the name starts with @name@-")
    }

    @Test
    fun `validate key mappings should exist in keys`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        mockVault("foo")
        every { vaultProvider.findVaultKeys("paas", "foo", "latest.properties") } returns setOf("FOO")
        assertThat {
            createAuroraDeploymentContext(
                """{
              "secretVaults" : {
                  "foo" : {
                    "keys" : ["FOO"],
                    "keyMappings" : { "BAR" : "BAZ" }
                  }
              }
             }"""
            )
        }.singleApplicationErrorResult(
            "The secretVault keyMappings [BAR] were not found in keys"
        )
    }

    @Test
    fun `should get error if user does not have access to vault`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        every { vaultProvider.vaultExists("paas", "simple") } returns true

        every {
            vaultProvider.findFileInVault(
                "paas",
                "simple",
                "latest.properties"
            )
        } throws UnauthorizedAccessException("Your user does not have access")

        assertThat {
            createAuroraDeploymentContext(
                """{
              "secretVault" : "simple"
             }"""
            )
        }.singleApplicationErrorResult(
            "Your user does not have access"
        )
    }

    @Test
    fun `should get error if secrets have duplicate names`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        every { vaultProvider.vaultExists("paas", "simple") } returns true

        assertThat {
            createAuroraDeploymentContext(
                """{
              "secretVault" : "simple",
              "secretVaults" : {
                "simple" : {
                  "enabled" : "true"
                } 
              }
             }"""
            )
        }.applicationErrorResult(
            "File with name=latest.properties is not present in vault=simple in collection=paas",
            "File with name=latest.properties is not present in vault=simple in collection=paas",
            "SecretVaults does not have unique names=[simple, simple]"
        )
    }

    @Test
    fun `should get error if vault key doss not exist`() {

        every { vaultProvider.vaultExists("paas", "foo") } returns true

        every { vaultProvider.findVaultKeys("paas", "foo", "latest.properties") } returns setOf("FOO")

        assertThat {
            createAuroraDeploymentContext(
                """{
              "secretVault" : {
                "name" :"foo",
                "keys" : ["MISSING"]
              }
             }"""
            )
        }.applicationErrorResult(
            "The keys [MISSING] were not found in the secret vault=foo in collection=paas"
        )
    }

    @Test
    fun `should get error if vault is public`() {

        every { vaultProvider.vaultExists("paas", "foo") } returns true
        every { vaultProvider.findPublicVaults(any()) } returns listOf("foo")

        assertThat {
            createAuroraDeploymentContext(
                """{
              "secretVault" : "foo" 
             }"""
            )
        }.applicationErrorResult(
            "File with name=latest.properties is not present in vault=foo in collection=paas",
            "Vault=foo in VaultCollection=paas is public"
        )
    }

    @Test
    fun `should get error if vault does not exist`() {

        every { vaultProvider.vaultExists("paas", "foo") } returns false
        every { vaultProvider.findPublicVaults(any()) } returns emptyList()

        assertThat {
            createAuroraDeploymentContext(
                """{
              "secretVault" : "foo" 
             }"""
            )
        }.applicationErrorResult(
            "Referenced Vault foo in Vault Collection paas does not exist",
            "File with name=latest.properties is not present in vault=foo in collection=paas"
        )
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecret`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        mockVault("foo")
        val resource = generateResources(
            """{
              "secretVault" : "foo" 
             }""",
            createEmptyDeploymentConfig()
        )
        assertThat(resource.size).isEqualTo(2)
        val dcResource = resource.first()
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(1)
        val foo = env.first()

        val attachmentResource = resource.last()
        assertEnvVarMounted(attachmentResource, "FOO", foo)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecret normalize _ and uppercase letters`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        mockVault("Foo_Bar")
        val resource = generateResources(
            """{
              "secretVault" : "Foo_Bar" 
             }""",
            createEmptyDeploymentConfig()
        )
        assertThat(resource.size).isEqualTo(2)
        val dcResource = resource.first()
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(1)
        val foo = env.first()

        val attachmentResource = resource.last()
        assertThat(attachmentResource.resource.metadata.name).isEqualTo(attachmentResource.resource.metadata.name.lowercase())
        assertThat(attachmentResource.resource.metadata.name).isEqualTo("simple-foo-bar-vault")
        assertEnvVarMounted(attachmentResource, "FOO_BAR", foo)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecret and ignore key`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        every { vaultProvider.findVaultKeys("paas", "foo", "latest.properties") } returns setOf("FOO", "BAR")

        mockVault("foo", contents = "FOO=secretValue\nBAR=value\n")
        val resource = generateResources(
            """{
              "secretVault": {
                "name" : "foo",
                "keys" : ["FOO"] 
               }
             }""",
            createEmptyDeploymentConfig()
        )
        assertThat(resource.size).isEqualTo(2)
        val dcResource = resource.first()
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(1)
        val foo = env.first()

        val attachmentResource = resource.last()
        assertEnvVarMounted(attachmentResource, "FOO", foo)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecret with key with placeholder`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        every { vaultProvider.findVaultKeys("paas", "simple", "latest.properties") } returns setOf("simple", "FOO")
        mockVault("simple", contents = "simple=secret\nFOO=bar\n")
        val resource = generateResources(
            """{
              "secretVaults" : {
                "simple": {
                  "keys" : ["@name@"]
                }
              }
             }""",
            createEmptyDeploymentConfig()
        )
        assertThat(resource.size).isEqualTo(2)
        val dcResource = resource.first()
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(1)
        val foo = env.first()

        val attachmentResource = resource.last()
        assertEnvVarMounted(attachmentResource, "simple", foo)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecret with mapped key`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        mockVault("simple", contents = "simple=secretValue\n")
        val resource = generateResources(
            """{
              "secretVaults" : {
                "simple": {
                  "keyMappings" : { "@name@" : "SIMPLE_2" }
                }
              }
             }""",
            createEmptyDeploymentConfig()
        )
        assertThat(resource.size).isEqualTo(2)
        val dcResource = resource.first()
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(1)
        val foo = env.first()

        val attachmentResource = resource.last()
        assertEnvVarMounted(attachmentResource, "SIMPLE_2", foo)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecret from custom file`() {

        mockVault("foo", "foo.properties")
        every { vaultProvider.findPublicVaults(any()) } returns emptyList()

        val resource = generateResources(
            """{
              "secretVaults" : {
                "foo": {
                  "file" : "foo.properties"
                }
              }
             }""",
            createEmptyDeploymentConfig()
        )
        assertThat(resource.size).isEqualTo(2)
        val dcResource = resource.first()
        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(1)
        val foo = env.first()

        val attachmentResource = resource.last()
        assertEnvVarMounted(attachmentResource, "FOO", foo)
    }

    @Test
    fun `should skip disabled mount`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        val resource = generateResources(
            """{
              "secretVaults" : {
                 "foo": {
                  "enabled" : false
                  }
              }
             }"""
        )
        assertThat(resource.size).isEqualTo(0)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecrets`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        mockVault("foo")
        mockVault("bar")

        val (dcResource, fooResource, barResource) = generateResources(
            """{
              "secretVaults" : {
                "foo" : {
                   "enabled" : true
                 }, 
                 "bar" : {
                   "enabled" : true
                 }
              }
             }""",
            createdResources = 2, resource = createEmptyDeploymentConfig()
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig
        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(2)
        val (fooEnv, barEnv) = env.toList()

        assertEnvVarMounted(barResource, "BAR", barEnv)
        assertEnvVarMounted(fooResource, "FOO", fooEnv)
    }

    @Test
    fun `should modify deploymentConfig and add auroraVaultSecrets from simple and expanded syntax`() {

        every { vaultProvider.findPublicVaults(any()) } returns emptyList()
        mockVault("foo")
        mockVault("bar")

        val (dcResource, fooResource, barResource) = generateResources(
            """{
              "secretVault" : "bar",
              "secretVaults" : {
                 "foo" : {
                   "enabled" : true
                 }
              }
             }""",
            createdResources = 2, resource = createEmptyDeploymentConfig()
        )

        assertThat(dcResource).auroraResourceModifiedByThisFeatureWithComment("Added env vars")
        val dc = dcResource.resource as DeploymentConfig

        val env = dc.spec.template.spec.containers.first().env
        assertThat(env.size).isEqualTo(2)

        val (fooEnv, barEnv) = env.toList()
        assertEnvVarMounted(fooResource, "FOO", fooEnv)
        assertEnvVarMounted(barResource, "BAR", barEnv)
    }

    private fun mockVault(
        name: String,
        fileName: String = "latest.properties",
        contents: String = "${name.uppercase()}=secretValue\n"
    ) {
        val vaultContents1 = contents.toByteArray()
        every { vaultProvider.vaultExists("paas", name) } returns true
        every {
            vaultProvider.findFileInVault(
                vaultCollectionName = "paas",
                vaultName = name,
                fileName = fileName
            )
        } returns vaultContents1

        every { vaultProvider.findVaultDataSingle(VaultRequest(collectionName = "paas", name = name)) } returns
            mapOf(fileName to vaultContents1)
    }

    private fun assertEnvVarMounted(
        resource: AuroraResource,
        envName: String,
        envVar: EnvVar
    ) {
        val foo = resource.resource as Secret
        assertThat(foo.data.keys.toList()).isEqualTo(listOf(envName))
        assertThat(resource).auroraResourceCreatedByThisFeature()
        assertThat(envVar.name).isEqualTo(envName)
        assertThat(envVar.valueFrom.secretKeyRef.key).isEqualTo(envName)
        assertThat(envVar.valueFrom.secretKeyRef.name).isEqualTo(resource.resource.metadata.name)
    }
}
